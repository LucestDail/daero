package com.daero.gtfs;

import com.daero.gtfs.model.*;
import com.daero.gtfs.util.CsvReader;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * GTFS(디렉토리 또는 .zip) → {@link GtfsFeed} 로더.
 * 핵심 파일: stops/routes/trips/stop_times/calendar/calendar_dates/transfers.
 * 데이터 경로가 없으면 빈 피드로 기동한다(서버는 정상 구동).
 */
@Slf4j
@Component
public class GtfsLoader {

    private static final DateTimeFormatter GTFS_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final String gtfsPath;
    private final String binPath;
    private volatile GtfsFeed feed = GtfsFeed.empty();

    public GtfsLoader(@Value("${daero.gtfs.path:}") String gtfsPath,
                      @Value("${daero.timetable.bin:}") String binPath) {
        this.gtfsPath = gtfsPath;
        this.binPath = binPath;
    }

    public GtfsFeed getFeed() {
        return feed;
    }

    /** 빌드 후 원본 피드를 해제해 상주 메모리를 낮춘다(라우팅엔 Timetable만 필요). */
    public void releaseFeed() {
        this.feed = GtfsFeed.empty();
    }

    @PostConstruct
    public void loadOnStartup() {
        // prebuild 스냅샷이 있으면 GTFS 파싱 자체를 건너뛴다(기동 메모리 피크 회피).
        if (binPath != null && !binPath.isBlank() && Files.exists(Paths.get(binPath))) {
            log.info("[gtfs] prebuilt timetable({}) 존재 → GTFS 로딩 스킵", binPath);
            return;
        }
        if (gtfsPath == null || gtfsPath.isBlank()) {
            log.warn("[gtfs] daero.gtfs.path 미설정 → 빈 피드로 기동");
            return;
        }
        Path path = Paths.get(gtfsPath);
        if (!Files.exists(path)) {
            log.warn("[gtfs] 경로 없음: {} → 빈 피드로 기동 (데이터 넣고 재기동)", path.toAbsolutePath());
            return;
        }
        try {
            long t0 = System.currentTimeMillis();
            this.feed = load(path);
            log.info("[gtfs] 로드 완료 ({}ms): stops={}, routes={}, trips={}, stopTimes={}, calendars={}, transfers={}",
                    System.currentTimeMillis() - t0,
                    feed.stops().size(), feed.routes().size(), feed.trips().size(),
                    feed.stopTimes().size(), feed.calendars().size(), feed.transfers().size());
        } catch (Exception e) {
            log.error("[gtfs] 로드 실패: {} → 빈 피드 유지", e.getMessage(), e);
        }
    }

    /** 디렉토리/zip 로부터 전체 피드 로드. */
    public GtfsFeed load(Path path) throws IOException {
        Source src = Files.isDirectory(path) ? new DirSource(path) : new ZipSource(path);
        try {
            Map<String, Stop> stops = new HashMap<>();
            Map<String, Route> routes = new HashMap<>();
            Map<String, Trip> trips = new HashMap<>();
            List<StopTime> stopTimes = new ArrayList<>(1 << 21); // 전국 ~2200만 행 대비 사전할당
            List<Transfer> transfers = new ArrayList<>();
            // stopId/tripId 는 stop_times 에서 수천만 번 반복 → 인터닝으로 String 중복 제거(메모리 급감).
            Map<String, String> intern = new HashMap<>(1 << 20);

            readEach(src, "stops.txt", r -> {
                String id = r.get("stop_id");
                if (id == null) return;
                stops.put(id, new Stop(id, opt(r, "stop_name"),
                        parseD(r.get("stop_lat")), parseD(r.get("stop_lon"))));
            });
            readEach(src, "routes.txt", r -> {
                String id = r.get("route_id");
                if (id == null) return;
                routes.put(id, new Route(id, opt(r, "route_short_name"),
                        opt(r, "route_long_name"), parseI(r.get("route_type"), 3)));
            });
            readEach(src, "trips.txt", r -> {
                String id = r.get("trip_id");
                if (id == null) return;
                trips.put(id, new Trip(id, r.get("route_id"), r.get("service_id"), opt(r, "trip_headsign")));
            });
            readEach(src, "stop_times.txt", r -> {
                String tripId = r.get("trip_id");
                String stopId = r.get("stop_id");
                if (tripId == null || stopId == null) return;
                int dep = parseTime(r.get("departure_time"));
                int arr = parseTime(r.get("arrival_time"));
                if (arr < 0) arr = dep;
                if (dep < 0) dep = arr;
                tripId = intern.computeIfAbsent(tripId, k -> k);
                stopId = intern.computeIfAbsent(stopId, k -> k);
                stopTimes.add(new StopTime(tripId, parseI(r.get("stop_sequence"), 0), stopId, arr, dep));
            });
            Map<String, ServiceCalendar> calendars = loadCalendars(src);
            readEach(src, "transfers.txt", r -> {
                String from = r.get("from_stop_id");
                String to = r.get("to_stop_id");
                if (from == null || to == null) return;
                transfers.add(new Transfer(from, to, parseI(r.get("min_transfer_time"), 0)));
            });

            return new GtfsFeed(stops, routes, trips, stopTimes, calendars, transfers);
        } finally {
            src.close();
        }
    }

    private Map<String, ServiceCalendar> loadCalendars(Source src) throws IOException {
        // calendar.txt: 요일/기간
        Map<String, DayOfWeek[]> dowMap = new HashMap<>();
        Map<String, LocalDate[]> spanMap = new HashMap<>();
        Map<String, Set<LocalDate>> added = new HashMap<>();
        Map<String, Set<LocalDate>> removed = new HashMap<>();

        readEach(src, "calendar.txt", r -> {
            String sid = r.get("service_id");
            if (sid == null) return;
            List<DayOfWeek> days = new ArrayList<>();
            DayOfWeek[] all = DayOfWeek.values(); // MON..SUN
            String[] keys = {"monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"};
            for (int i = 0; i < 7; i++) if ("1".equals(r.get(keys[i]))) days.add(all[i]);
            dowMap.put(sid, days.toArray(new DayOfWeek[0]));
            spanMap.put(sid, new LocalDate[]{parseDate(r.get("start_date")), parseDate(r.get("end_date"))});
        });
        readEach(src, "calendar_dates.txt", r -> {
            String sid = r.get("service_id");
            LocalDate d = parseDate(r.get("date"));
            if (sid == null || d == null) return;
            if ("2".equals(r.get("exception_type"))) removed.computeIfAbsent(sid, k -> new HashSet<>()).add(d);
            else added.computeIfAbsent(sid, k -> new HashSet<>()).add(d);
        });

        Map<String, ServiceCalendar> out = new HashMap<>();
        Set<String> ids = new HashSet<>();
        ids.addAll(dowMap.keySet());
        ids.addAll(added.keySet());
        ids.addAll(removed.keySet());
        for (String sid : ids) {
            DayOfWeek[] dows = dowMap.getOrDefault(sid, new DayOfWeek[0]);
            LocalDate[] span = spanMap.getOrDefault(sid, new LocalDate[]{null, null});
            out.put(sid, new ServiceCalendar(sid, Set.of(dows), span[0], span[1],
                    added.getOrDefault(sid, Set.of()), removed.getOrDefault(sid, Set.of())));
        }
        return out;
    }

    private void readEach(Source src, String name, java.util.function.Consumer<Map<String, String>> fn) throws IOException {
        try (BufferedReader r = src.open(name)) {
            if (r == null) {
                log.debug("[gtfs] {} 없음(스킵)", name);
                return;
            }
            CsvReader.forEachRow(r, fn);
        }
    }

    // ── 파싱 헬퍼 ────────────────────────────────────────────
    private static String opt(Map<String, String> r, String k) {
        String v = r.get(k);
        return v == null ? "" : v;
    }

    private static double parseD(String s) {
        try { return s == null || s.isBlank() ? 0 : Double.parseDouble(s); }
        catch (NumberFormatException e) { return 0; }
    }

    private static int parseI(String s, int dflt) {
        try { return s == null || s.isBlank() ? dflt : Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return dflt; }
    }

    /** "HH:MM:SS"(24h 초과 가능) → 자정기준 초. 빈값이면 -1. */
    static int parseTime(String s) {
        if (s == null || s.isBlank()) return -1;
        String[] p = s.split(":");
        if (p.length < 2) return -1;
        try {
            int h = Integer.parseInt(p[0].trim());
            int m = Integer.parseInt(p[1].trim());
            int sec = p.length >= 3 ? Integer.parseInt(p[2].trim()) : 0;
            return h * 3600 + m * 60 + sec;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static LocalDate parseDate(String s) {
        try { return s == null || s.isBlank() ? null : LocalDate.parse(s.trim(), GTFS_DATE); }
        catch (Exception e) { return null; }
    }

    // ── 소스 추상화 (디렉토리 / zip) ─────────────────────────
    private interface Source extends Closeable {
        /** 파일명에 대한 리더. 없으면 null. */
        BufferedReader open(String name) throws IOException;
    }

    private static class DirSource implements Source {
        private final Path dir;
        DirSource(Path dir) { this.dir = dir; }
        public BufferedReader open(String name) throws IOException {
            Path p = dir.resolve(name);
            if (!Files.exists(p)) return null;
            return Files.newBufferedReader(p, StandardCharsets.UTF_8);
        }
        public void close() {}
    }

    private static class ZipSource implements Source {
        private final ZipFile zip;
        // 엔트리명 디코딩은 ISO-8859-1(바이트 1:1, 실패 없음). KTDB zip 은 한글 파일명이 CP949라
        // 기본 UTF-8 로는 "invalid CEN header" 발생. 우리가 접근하는 GTFS 엔트리명은 ASCII 라 무방.
        // (파일 내용은 open() 에서 UTF-8 로 별도 디코딩)
        ZipSource(Path zipPath) throws IOException {
            this.zip = new ZipFile(zipPath.toFile(), StandardCharsets.ISO_8859_1);
        }
        public BufferedReader open(String name) throws IOException {
            ZipEntry e = zip.getEntry(name);
            if (e == null) {
                // GTFS 가 zip 내부 하위 폴더(예: 202403_GTFS_DataSet/)에 있으면 접미어로 매칭.
                var it = zip.entries();
                while (it.hasMoreElements()) {
                    ZipEntry c = it.nextElement();
                    if (!c.isDirectory() && c.getName().endsWith("/" + name)) { e = c; break; }
                }
            }
            if (e == null) return null;
            return new BufferedReader(new InputStreamReader(zip.getInputStream(e), StandardCharsets.UTF_8));
        }
        public void close() throws IOException { zip.close(); }
    }
}
