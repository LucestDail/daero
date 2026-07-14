package com.daero.raptor;

import com.daero.gtfs.GtfsLoader;
import com.daero.gtfs.model.GtfsFeed;
import com.daero.gtfs.model.Route;
import com.daero.gtfs.model.StopTime;
import com.daero.gtfs.model.Transfer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * GTFS 원본({@link GtfsFeed}) → 컬럼형 {@link Timetable} 변환기.
 * 앱 기동 완료 후(ApplicationReadyEvent) 1회 빌드하여 상주시킨다.
 *
 * <p>핵심: trip 들을 "동일 정차순서(pattern)"로 묶어 RAPTOR route 로 만들고,
 * 시각을 평면 int 배열로 펴며, 정류장→패턴 역인덱스와 도보 환승을 사전계산한다.
 */
@Slf4j
@Component
public class TimetableBuilder {

    // 좌표 기반 도보 환승 파라미터 (버스↔지하철역 연결 위해 반경 확대)
    private static final double TRANSFER_RADIUS_M = 350;
    private static final double WALK_SPEED = 1.2;      // m/s
    private static final int MIN_TRANSFER_SEC = 60;
    private static final int MAX_TRANSFERS_PER_STOP = 16;

    private final GtfsLoader loader;
    private volatile Timetable timetable;
    private volatile long buildMs = -1;

    public TimetableBuilder(GtfsLoader loader) {
        this.loader = loader;
    }

    public Timetable getTimetable() { return timetable; }
    public long getBuildMs() { return buildMs; }

    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    public void build() {
        GtfsFeed feed = loader.getFeed();
        if (feed == null || feed.isEmpty()) {
            log.warn("[timetable] GTFS 비어있음 → 빌드 스킵");
            return;
        }
        long t0 = System.currentTimeMillis();

        // 1) 정류장 인덱싱
        int nStops = feed.stops().size();
        String[] stopId = new String[nStops];
        String[] stopName = new String[nStops];
        double[] lat = new double[nStops];
        double[] lon = new double[nStops];
        Map<String, Integer> stopIndex = new HashMap<>(nStops * 2);
        int si = 0;
        for (var e : feed.stops().entrySet()) {
            stopIndex.put(e.getKey(), si);
            stopId[si] = e.getKey();
            stopName[si] = e.getValue().name();
            lat[si] = e.getValue().lat();
            lon[si] = e.getValue().lon();
            si++;
        }
        log.info("[timetable] 정류장 인덱싱 {}개", nStops);

        // 2) stop_times 를 trip 별로 묶기
        Map<String, List<StopTime>> byTrip = new HashMap<>(feed.trips().size() * 2);
        for (StopTime st : feed.stopTimes()) {
            byTrip.computeIfAbsent(st.tripId(), k -> new ArrayList<>()).add(st);
        }
        log.info("[timetable] trip 그룹화 {}개", byTrip.size());

        // 3) trip → pattern(동일 정차순서) 그룹화
        Map<String, Integer> patternId = new HashMap<>();
        List<int[]> patStops = new ArrayList<>();         // 패턴별 정류장 인덱스열
        List<Integer> patMode = new ArrayList<>();
        List<String> patRouteName = new ArrayList<>();    // 패턴별 대표 노선명
        List<List<int[]>> patTrips = new ArrayList<>();   // 패턴별 trip 시각열(int[2L]: [0..L)=arr,[L..2L)=dep)

        for (var e : byTrip.entrySet()) {
            List<StopTime> sts = e.getValue();
            sts.sort(Comparator.comparingInt(StopTime::seq));
            int L = sts.size();
            if (L < 2) continue; // 정차 1개 이하는 경로에 무의미

            int[] stopsSeq = new int[L];
            int[] times = new int[2 * L];
            StringBuilder key = new StringBuilder(L * 7);
            for (int i = 0; i < L; i++) {
                StopTime st = sts.get(i);
                Integer idx = stopIndex.get(st.stopId());
                if (idx == null) { stopsSeq = null; break; } // 미상 정류장 → 스킵
                stopsSeq[i] = idx;
                times[i] = st.arrivalSec();
                times[L + i] = st.departureSec();
                key.append(idx).append(',');
            }
            if (stopsSeq == null) continue;

            int mode = modeOf(feed, e.getKey());
            key.append('#').append(mode);
            String k = key.toString();
            Integer pid = patternId.get(k);
            if (pid == null) {
                pid = patStops.size();
                patternId.put(k, pid);
                patStops.add(stopsSeq);
                patMode.add(mode);
                patRouteName.add(routeNameOf(feed, e.getKey()));
                patTrips.add(new ArrayList<>());
            }
            patTrips.get(pid).add(times);
        }
        int nPatterns = patStops.size();
        log.info("[timetable] 패턴 {}개 (trip {}개 압축)", nPatterns, byTrip.size());

        // 4) 평면화: patternStops / patternTimeOffset / arr / dep
        int[] patternStopOffset = new int[nPatterns + 1];
        int[] patternMode = new int[nPatterns];
        String[] patternRouteName = patRouteName.toArray(new String[0]);
        int[] patternNumTrips = new int[nPatterns];
        int[] patternTimeOffset = new int[nPatterns + 1];
        long totalStops = 0, totalTimes = 0;
        for (int p = 0; p < nPatterns; p++) {
            int L = patStops.get(p).length;
            int T = patTrips.get(p).size();
            patternStopOffset[p + 1] = patternStopOffset[p] + L;
            patternTimeOffset[p + 1] = patternTimeOffset[p] + L * T;
            patternMode[p] = patMode.get(p);
            patternNumTrips[p] = T;
            totalStops += L;
            totalTimes += (long) L * T;
        }
        int[] patternStops = new int[(int) totalStops];
        int[] arr = new int[(int) totalTimes];
        int[] dep = new int[(int) totalTimes];
        for (int p = 0; p < nPatterns; p++) {
            int L = patStops.get(p).length;
            System.arraycopy(patStops.get(p), 0, patternStops, patternStopOffset[p], L);
            // trip 을 출발시각(첫 정차 dep) 순으로 정렬 → RAPTOR 가 최선 trip 을 빨리 찾음
            List<int[]> trips = patTrips.get(p);
            trips.sort(Comparator.comparingInt(t -> t[L])); // t[L] = dep[0]
            int base = patternTimeOffset[p];
            for (int t = 0; t < trips.size(); t++) {
                int[] tm = trips.get(t);
                for (int i = 0; i < L; i++) {
                    arr[base + t * L + i] = tm[i];
                    dep[base + t * L + i] = tm[L + i];
                }
            }
        }

        // 5) 정류장→패턴 역인덱스
        int[] stopPatCount = new int[nStops + 1];
        for (int p = 0; p < nPatterns; p++)
            for (int i = patternStopOffset[p]; i < patternStopOffset[p + 1]; i++)
                stopPatCount[patternStops[i] + 1]++;
        for (int s = 0; s < nStops; s++) stopPatCount[s + 1] += stopPatCount[s];
        int[] stopPatOffset = stopPatCount.clone();
        int[] stopPatId = new int[(int) totalStops];
        int[] stopPatPos = new int[(int) totalStops];
        int[] cursor = stopPatOffset.clone();
        for (int p = 0; p < nPatterns; p++) {
            int off = patternStopOffset[p];
            int L = patternStopOffset[p + 1] - off;
            for (int i = 0; i < L; i++) {
                int s = patternStops[off + i];
                int c = cursor[s]++;
                stopPatId[c] = p;
                stopPatPos[c] = i;
            }
        }

        // 6) 도보 환승: GTFS transfers + 좌표 기반 근접(그리드)
        int[][] tf = buildTransfers(feed, stopIndex, nStops, lat, lon);

        this.timetable = new Timetable(nStops, stopId, stopName, lat, lon, stopIndex,
                nPatterns, patternStopOffset, patternStops, patternMode, patternRouteName, patternNumTrips,
                patternTimeOffset, arr, dep, stopPatOffset, stopPatId, stopPatPos,
                tf[0], tf[1], tf[2]);
        this.buildMs = System.currentTimeMillis() - t0;
        log.info("[timetable] 빌드 완료 ({}ms): patterns={}, 정차엔트리={}, 환승={}",
                buildMs, nPatterns, totalTimes, tf[1].length);
    }

    private int modeOf(GtfsFeed feed, String tripId) {
        var trip = feed.trips().get(tripId);
        if (trip == null) return Route.Mode.BUS.ordinal();
        var route = feed.routes().get(trip.routeId());
        return route == null ? Route.Mode.BUS.ordinal() : route.mode().ordinal();
    }

    private String routeNameOf(GtfsFeed feed, String tripId) {
        var trip = feed.trips().get(tripId);
        if (trip == null) return "";
        var route = feed.routes().get(trip.routeId());
        if (route == null) return "";
        String n = route.shortName();
        if (n == null || n.isBlank() || "-".equals(n)) n = route.longName();
        return n == null ? "" : n;
    }

    /** 환승 배열 3종 [offset(nStops+1), target, sec] 생성. */
    private int[][] buildTransfers(GtfsFeed feed, Map<String, Integer> stopIndex, int nStops,
                                   double[] lat, double[] lon) {
        List<int[]>[] adj = new List[nStops];
        for (int s = 0; s < nStops; s++) adj[s] = new ArrayList<>();

        // (a) GTFS transfers.txt
        for (Transfer t : feed.transfers()) {
            Integer f = stopIndex.get(t.fromStopId());
            Integer to = stopIndex.get(t.toStopId());
            if (f == null || to == null || f.equals(to)) continue;
            adj[f].add(new int[]{to, Math.max(MIN_TRANSFER_SEC, t.minTransferSec())});
        }

        // (b) 좌표 기반 근접 환승 (그리드)
        final double cell = 0.0018; // 약 200m
        Map<Long, List<Integer>> grid = new HashMap<>();
        for (int s = 0; s < nStops; s++) {
            long k = (((long) (int) Math.floor(lat[s] / cell)) << 32)
                    ^ ((int) Math.floor(lon[s] / cell) & 0xffffffffL);
            grid.computeIfAbsent(k, x -> new ArrayList<>()).add(s);
        }
        for (int s = 0; s < nStops; s++) {
            int clat = (int) Math.floor(lat[s] / cell);
            int clon = (int) Math.floor(lon[s] / cell);
            int added = 0;
            for (int dla = -2; dla <= 2 && added < MAX_TRANSFERS_PER_STOP; dla++) {
                for (int dlo = -2; dlo <= 2 && added < MAX_TRANSFERS_PER_STOP; dlo++) {
                    List<Integer> cellStops = grid.get((((long) (clat + dla)) << 32) ^ ((clon + dlo) & 0xffffffffL));
                    if (cellStops == null) continue;
                    for (int o : cellStops) {
                        if (o == s || added >= MAX_TRANSFERS_PER_STOP) continue;
                        double d = Timetable.distanceMeters(lat[s], lon[s], lat[o], lon[o]);
                        if (d <= TRANSFER_RADIUS_M) {
                            adj[s].add(new int[]{o, Math.max(MIN_TRANSFER_SEC, (int) Math.round(d / WALK_SPEED))});
                            added++;
                        }
                    }
                }
            }
        }

        int[] offset = new int[nStops + 1];
        for (int s = 0; s < nStops; s++) offset[s + 1] = offset[s] + adj[s].size();
        int total = offset[nStops];
        int[] target = new int[total];
        int[] sec = new int[total];
        for (int s = 0; s < nStops; s++) {
            int base = offset[s];
            List<int[]> list = adj[s];
            for (int i = 0; i < list.size(); i++) {
                target[base + i] = list.get(i)[0];
                sec[base + i] = list.get(i)[1];
            }
        }
        return new int[][]{offset, target, sec};
    }
}
