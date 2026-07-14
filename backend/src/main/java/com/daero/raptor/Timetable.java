package com.daero.raptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAPTOR용 컬럼형(compact) 대중교통 시각표.
 * GTFS 원본을 {@link TimetableBuilder} 가 이 구조로 변환한다. 모두 정수 인덱스·평면 배열이라
 * 전국 규모(2200만 정차)에서도 캐시 친화적이고 메모리가 작다.
 *
 * <p>용어: 여기서 "pattern"(정차패턴) = 동일한 정차 순서를 공유하는 trip 묶음 = RAPTOR의 route.
 */
public final class Timetable {

    // ── 정류장 ──────────────────────────────────────────────
    public final int nStops;
    public final String[] stopId;
    public final String[] stopName;
    public final double[] lat;
    public final double[] lon;
    public final Map<String, Integer> stopIndex;

    // ── 정차패턴(pattern = RAPTOR route) ────────────────────
    public final int nPatterns;
    public final int[] patternStopOffset;  // [nPatterns+1] patternStops 슬라이스 경계
    public final int[] patternStops;        // 평면화된 정류장 인덱스(패턴별 정차 순서)
    public final int[] patternMode;         // 패턴별 Route.Mode.ordinal
    public final String[] patternRouteName; // 패턴별 대표 노선명(표시용)
    public final int[] patternNumTrips;     // [nPatterns] 패턴별 trip 수
    public final int[] patternTimeOffset;   // [nPatterns+1] arr/dep 슬라이스 시작(정차 엔트리 단위)
    public final int[] arr;                 // 평면 도착초: patternTimeOffset[p] + t*L + i
    public final int[] dep;                 // 평면 출발초

    // ── 정류장→패턴 역인덱스 ────────────────────────────────
    public final int[] stopPatOffset;       // [nStops+1]
    public final int[] stopPatId;           // 이 정류장을 지나는 패턴 id
    public final int[] stopPatPos;          // 해당 패턴 내 이 정류장의 위치(index)

    // ── 도보 환승 ───────────────────────────────────────────
    public final int[] transferOffset;      // [nStops+1]
    public final int[] transferTarget;      // 대상 정류장 인덱스
    public final int[] transferSec;         // 도보 소요초

    // ── 좌표 공간 인덱스(그리드) — door-to-door 접근 정류장 탐색용 ──
    private static final double CELL_DEG = 0.0045; // 약 500m
    private final Map<Long, int[]> grid;

    Timetable(int nStops, String[] stopId, String[] stopName, double[] lat, double[] lon,
              Map<String, Integer> stopIndex,
              int nPatterns, int[] patternStopOffset, int[] patternStops, int[] patternMode,
              String[] patternRouteName, int[] patternNumTrips, int[] patternTimeOffset, int[] arr, int[] dep,
              int[] stopPatOffset, int[] stopPatId, int[] stopPatPos,
              int[] transferOffset, int[] transferTarget, int[] transferSec) {
        this.nStops = nStops; this.stopId = stopId; this.stopName = stopName;
        this.lat = lat; this.lon = lon; this.stopIndex = stopIndex;
        this.nPatterns = nPatterns; this.patternStopOffset = patternStopOffset;
        this.patternStops = patternStops; this.patternMode = patternMode;
        this.patternRouteName = patternRouteName;
        this.patternNumTrips = patternNumTrips; this.patternTimeOffset = patternTimeOffset;
        this.arr = arr; this.dep = dep;
        this.stopPatOffset = stopPatOffset; this.stopPatId = stopPatId; this.stopPatPos = stopPatPos;
        this.transferOffset = transferOffset; this.transferTarget = transferTarget; this.transferSec = transferSec;
        this.grid = buildGrid(nStops, lat, lon);
    }

    /** 패턴 p 의 정차 개수. */
    public int patternLength(int p) {
        return patternStopOffset[p + 1] - patternStopOffset[p];
    }

    /** 두 좌표 간 거리(m) — Haversine. */
    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** 좌표 근방 정류장 탐색(반경 m 내). 반환: [stopIndex, 도보초] 목록. walkSpeed m/s. */
    public List<int[]> nearestStops(double qlat, double qlon, double radiusM, double walkSpeed) {
        List<int[]> out = new ArrayList<>();
        int cellR = (int) Math.ceil(radiusM / (CELL_DEG * 111000)) + 1;
        int clat = (int) Math.floor(qlat / CELL_DEG);
        int clon = (int) Math.floor(qlon / CELL_DEG);
        for (int dla = -cellR; dla <= cellR; dla++) {
            for (int dlo = -cellR; dlo <= cellR; dlo++) {
                int[] cell = grid.get(key(clat + dla, clon + dlo));
                if (cell == null) continue;
                for (int s : cell) {
                    double d = distanceMeters(qlat, qlon, lat[s], lon[s]);
                    if (d <= radiusM) out.add(new int[]{s, (int) Math.round(d / walkSpeed)});
                }
            }
        }
        return out;
    }

    private static Map<Long, int[]> buildGrid(int nStops, double[] lat, double[] lon) {
        Map<Long, List<Integer>> tmp = new HashMap<>();
        for (int s = 0; s < nStops; s++) {
            long k = key((int) Math.floor(lat[s] / CELL_DEG), (int) Math.floor(lon[s] / CELL_DEG));
            tmp.computeIfAbsent(k, x -> new ArrayList<>()).add(s);
        }
        Map<Long, int[]> g = new HashMap<>(tmp.size() * 2);
        for (var e : tmp.entrySet()) {
            int[] a = new int[e.getValue().size()];
            for (int i = 0; i < a.length; i++) a[i] = e.getValue().get(i);
            g.put(e.getKey(), a);
        }
        return g;
    }

    private static long key(int latCell, int lonCell) {
        return (((long) latCell) << 32) ^ (lonCell & 0xffffffffL);
    }
}
