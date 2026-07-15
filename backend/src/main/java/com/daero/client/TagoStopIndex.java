package com.daero.client;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 전국 버스정류장 위치정보(국토교통부 CSV, CP949) → 좌표 인덱스.
 * daero(KTDB) 정류장 좌표로 가장 가까운 TAGO 정류장의 nodeId·cityCode 를 찾아
 * 실시간 도착조회({@link TagoClient})의 다리 역할. CSV 컬럼: 정류장번호,정류장명,위도,경도,정보수집일,모바일단축번호,도시코드,...
 */
@Slf4j
@Component
public class TagoStopIndex {

    private static final double CELL = 0.0027; // 약 300m 그리드
    private final String csvPath;

    private String[] nodeId = new String[0];
    private String[] cityCode = new String[0];
    private double[] lat = new double[0];
    private double[] lon = new double[0];
    private final Map<Long, int[]> grid = new HashMap<>();
    private volatile boolean loaded = false;

    public TagoStopIndex(@Value("${tago.stops.path:}") String csvPath) {
        this.csvPath = csvPath;
    }

    public boolean isLoaded() { return loaded; }
    public int size() { return nodeId.length; }

    /** 좌표 근방(반경 m) 최근접 TAGO 정류장. 없으면 null. 반환 [nodeId, cityCode]. */
    public String[] nearest(double qlat, double qlon, double radiusM) {
        if (!loaded) return null;
        int clat = (int) Math.floor(qlat / CELL), clon = (int) Math.floor(qlon / CELL);
        int best = -1; double bestD = radiusM;
        for (int dla = -1; dla <= 1; dla++) {
            for (int dlo = -1; dlo <= 1; dlo++) {
                int[] cell = grid.get(key(clat + dla, clon + dlo));
                if (cell == null) continue;
                for (int i : cell) {
                    double d = dist(qlat, qlon, lat[i], lon[i]);
                    if (d < bestD) { bestD = d; best = i; }
                }
            }
        }
        return best < 0 ? null : new String[]{nodeId[best], cityCode[best]};
    }

    @PostConstruct
    public void load() {
        if (csvPath == null || csvPath.isBlank()) { log.warn("[tago-stops] tago.stops.path 미설정 → 매핑 비활성"); return; }
        Path p = Paths.get(csvPath);
        if (!Files.exists(p)) { log.warn("[tago-stops] CSV 없음: {} → 매핑 비활성", p.toAbsolutePath()); return; }
        try {
            long t0 = System.currentTimeMillis();
            List<String> ids = new ArrayList<>(1 << 18);
            List<String> cities = new ArrayList<>(1 << 18);
            List<Double> lats = new ArrayList<>(1 << 18);
            List<Double> lons = new ArrayList<>(1 << 18);
            Charset cp949 = Charset.forName("MS949");
            try (BufferedReader r = new BufferedReader(new InputStreamReader(Files.newInputStream(p), cp949))) {
                r.readLine(); // 헤더
                String line;
                while ((line = r.readLine()) != null) {
                    String[] c = line.split(",");
                    if (c.length < 7) continue;
                    try {
                        double la = Double.parseDouble(c[2].trim());
                        double lo = Double.parseDouble(c[3].trim());
                        ids.add(c[0].trim()); cities.add(c[6].trim());
                        lats.add(la); lons.add(lo);
                    } catch (NumberFormatException ignore) { /* 좌표 파싱 실패 행 스킵 */ }
                }
            }
            int n = ids.size();
            nodeId = ids.toArray(new String[0]);
            cityCode = cities.toArray(new String[0]);
            lat = new double[n]; lon = new double[n];
            Map<Long, List<Integer>> tmp = new HashMap<>();
            for (int i = 0; i < n; i++) {
                lat[i] = lats.get(i); lon[i] = lons.get(i);
                tmp.computeIfAbsent(key((int) Math.floor(lat[i] / CELL), (int) Math.floor(lon[i] / CELL)),
                        k -> new ArrayList<>()).add(i);
            }
            for (var e : tmp.entrySet()) {
                int[] a = new int[e.getValue().size()];
                for (int i = 0; i < a.length; i++) a[i] = e.getValue().get(i);
                grid.put(e.getKey(), a);
            }
            loaded = true;
            log.info("[tago-stops] 로드 완료 ({}ms): {}개 정류장, 그리드 {}셀",
                    System.currentTimeMillis() - t0, n, grid.size());
        } catch (Exception e) {
            log.error("[tago-stops] 로드 실패: {}", e.getMessage());
        }
    }

    private static double dist(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static long key(int a, int b) { return (((long) a) << 32) ^ (b & 0xffffffffL); }
}
