package com.daero.web;

import com.daero.raptor.RaptorRouter;
import com.daero.raptor.Timetable;
import com.daero.raptor.TimetableBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 경로탐색 API: 정류장→정류장, 좌표 door-to-door, 정류장 검색. */
@RestController
@RequestMapping("/api/plan")
@RequiredArgsConstructor
public class PlanController {

    private static final double ACCESS_RADIUS_M = 1200; // 출발/도착 좌표에서 걸어갈 정류장 반경(지하철역이 성겨 확대)
    private static final double WALK_SPEED = 1.2;
    private static final int MAX_ACCESS_STOPS = 10;

    private final TimetableBuilder builder;
    private final RaptorRouter router;
    private final com.daero.client.TagoStopIndex tagoStops;
    private final com.daero.client.TagoClient tago;
    private final com.daero.client.SeoulBusClient seoulBus;

    /** 이름으로 정류장 검색. */
    @GetMapping("/search")
    public List<Map<String, Object>> search(@RequestParam String q, @RequestParam(defaultValue = "20") int limit) {
        Timetable tt = builder.getTimetable();
        List<Map<String, Object>> out = new ArrayList<>();
        if (tt == null) return out;
        for (int s = 0; s < tt.nStops && out.size() < limit; s++) {
            if (tt.stopName[s] != null && tt.stopName[s].contains(q)) {
                out.add(stopMap(tt, s));
            }
        }
        return out;
    }

    /** 좌표 근처 정류장 조회(진단용): 반경 내 정류장을 모드(ID 접두어)와 도보초와 함께. */
    @GetMapping("/near")
    public List<Map<String, Object>> near(@RequestParam double lat, @RequestParam double lon,
                                          @RequestParam(defaultValue = "800") double radius) {
        Timetable tt = builder.getTimetable();
        List<Map<String, Object>> out = new ArrayList<>();
        if (tt == null) return out;
        List<int[]> near = tt.nearestStops(lat, lon, radius, WALK_SPEED);
        near.sort(Comparator.comparingInt(a -> a[1]));
        for (int[] a : near) {
            int s = a[0];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stopId", tt.stopId[s]);
            m.put("prefix", tt.stopId[s].length() >= 2 ? tt.stopId[s].substring(0, 2) : "");
            m.put("name", tt.stopName[s]);
            m.put("walkSec", a[1]);
            out.add(m);
        }
        return out;
    }

    /** 정류장ID → 정류장ID. */
    @GetMapping
    public ResponseEntity<?> plan(@RequestParam String fromStop, @RequestParam String toStop,
                                  @RequestParam(required = false) String time,
                                  @RequestParam(defaultValue = "1") int alternatives) {
        Timetable tt = builder.getTimetable();
        if (tt == null) return ResponseEntity.status(503).body(Map.of("error", "timetable not built"));
        Integer src = tt.stopIndex.get(fromStop), dst = tt.stopIndex.get(toStop);
        if (src == null || dst == null)
            return ResponseEntity.badRequest().body(Map.of("error", "unknown stopId"));
        int departSec = parseTime(time);
        long t0 = System.nanoTime();
        int[] s1 = {src}, z = {0}, d1 = {dst};
        List<RaptorRouter.Result> normal = router.journeys(s1, z, d1, z, departSec, false);
        List<RaptorRouter.Result> rail = router.journeys(s1, z, d1, z, departSec, true);
        RaptorRouter.Result walk = walkJourney(tt.lat[src], tt.lon[src], tt.lat[dst], tt.lon[dst], departSec);
        return ResponseEntity.ok(renderTagged(tt, normal, rail, walk, departSec, clampAlts(alternatives), (System.nanoTime() - t0) / 1_000_000));
    }

    /** 좌표 → 좌표 door-to-door(접근·하차 도보 포함). */
    @GetMapping("/coords")
    public ResponseEntity<?> coords(@RequestParam double fromLat, @RequestParam double fromLon,
                                    @RequestParam double toLat, @RequestParam double toLon,
                                    @RequestParam(required = false) String time,
                                    @RequestParam(defaultValue = "1") int alternatives) {
        Timetable tt = builder.getTimetable();
        if (tt == null) return ResponseEntity.status(503).body(Map.of("error", "timetable not built"));
        List<int[]> src = topAccess(tt.nearestStops(fromLat, fromLon, ACCESS_RADIUS_M, WALK_SPEED));
        List<int[]> dst = topAccess(tt.nearestStops(toLat, toLon, ACCESS_RADIUS_M, WALK_SPEED));
        if (src.isEmpty() || dst.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "no nearby stops", "srcStops", src.size(), "dstStops", dst.size()));

        int[] ss = new int[src.size()], sa = new int[src.size()];
        for (int i = 0; i < src.size(); i++) { ss[i] = src.get(i)[0]; sa[i] = src.get(i)[1]; }
        int[] ds = new int[dst.size()], de = new int[dst.size()];
        for (int i = 0; i < dst.size(); i++) { ds[i] = dst.get(i)[0]; de[i] = dst.get(i)[1]; }

        int departSec = parseTime(time);
        long t0 = System.nanoTime();
        List<RaptorRouter.Result> normal = router.journeys(ss, sa, ds, de, departSec, false);
        List<RaptorRouter.Result> rail = router.journeys(ss, sa, ds, de, departSec, true);
        RaptorRouter.Result walk = walkJourney(fromLat, fromLon, toLat, toLon, departSec);
        Map<String, Object> m = renderTagged(tt, normal, rail, walk, departSec, clampAlts(alternatives), (System.nanoTime() - t0) / 1_000_000);
        m.put("accessStops", src.size());
        m.put("egressStops", dst.size());
        return ResponseEntity.ok(m);
    }

    // ── 렌더링/유틸 ─────────────────────────────────────────
    private int clampAlts(int n) {
        return Math.max(0, Math.min(10, n));
    }

    /**
     * 태그된 경로 옵션 구성. 항상 추천 1개 + 대안 최대 maxAlts 개(기본 1, 최대 10).
     * 우선순위: 추천(환승 페널티 최소) → 지하철·철도 우선 → 환승 최소 → 나머지 파레토(빠름 순).
     */
    private Map<String, Object> renderTagged(Timetable tt, List<RaptorRouter.Result> normal,
                                             List<RaptorRouter.Result> rail, RaptorRouter.Result walk,
                                             int departSec, int maxAlts, long queryMs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("queryMs", queryMs);
        if (normal.isEmpty() && walk == null) { m.put("found", false); return m; }

        // 추천 후보 = 전 모드 파레토 + 철도전용 파레토 + 도보.
        // 철도전용을 넣는 이유: 전 모드에선 근소하게 빠른 버스가 지하철을 파레토 지배해 탈락시키므로,
        // 지하철·철도 신뢰성 보너스(score)를 적용받을 기회를 주려면 후보에 직접 포함해야 함.
        List<RaptorRouter.Result> primaryCands = new ArrayList<>(normal);
        primaryCands.addAll(rail);
        if (walk != null) primaryCands.add(walk);
        RaptorRouter.Result primary = minScore(primaryCands);
        List<Map<String, Object>> opts = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        opts.add(oneJourney(tt, primary, departSec, "추천", true)); // 실시간은 추천에만
        seen.add(key(primary));

        // 후보를 우선순위대로 모아 중복 제거하며 maxAlts 까지 대안 추가
        List<Object[]> cands = new ArrayList<>(); // [Result, tag]
        if (walk != null) cands.add(new Object[]{walk, "도보"});
        RaptorRouter.Result railBest = rail.isEmpty() ? null : minScore(rail);
        if (railBest != null && usesRail(railBest)) cands.add(new Object[]{railBest, "지하철·철도 우선"});
        if (!normal.isEmpty()) cands.add(new Object[]{normal.get(0), "환승 최소"}); // 파레토 첫=환승 최소
        for (int i = normal.size() - 1; i >= 0; i--) cands.add(new Object[]{normal.get(i), "대안"}); // 빠른 순
        for (RaptorRouter.Result r : rail) cands.add(new Object[]{r, "철도 대안"});

        int primDur = primary.durationSec();
        for (Object[] c : cands) {
            if (opts.size() - 1 >= maxAlts) break;
            RaptorRouter.Result r = (RaptorRouter.Result) c[0];
            String tag = (String) c[1];
            // 비현실적으로 느린 대안 제거: 추천보다 1.8배 & 30분 이상 더 걸리는 경로는 노이즈(예: 완행 철도)로 간주
            if (!"도보".equals(tag) && r.durationSec() > primDur * 1.8 && r.durationSec() - primDur > 1800) continue;
            if (seen.add(key(r))) opts.add(oneJourney(tt, r, departSec, tag, false));
        }

        m.put("found", true);
        m.putAll(opts.get(0));      // 추천을 루트에 펼침(하위호환)
        m.put("options", opts);      // [추천, 대안...] 항상 최소 1개
        return m;
    }

    private static final double MAX_WALK_M = 2500; // 이 거리 이내면 직접 도보를 후보로 제시

    /** 출발→도착 직접 도보 여정. 너무 멀면(2.5km 초과) null. */
    private RaptorRouter.Result walkJourney(double fromLat, double fromLon, double toLat, double toLon, int departSec) {
        double dist = Timetable.distanceMeters(fromLat, fromLon, toLat, toLon);
        if (dist > MAX_WALK_M) return null;
        int walkSec = (int) Math.round(dist / WALK_SPEED);
        List<double[]> path = List.of(new double[]{fromLat, fromLon}, new double[]{toLat, toLon});
        RaptorRouter.Leg leg = new RaptorRouter.Leg("WALK", "", "", "출발지", "", "도착지", departSec, departSec + walkSec, path);
        return new RaptorRouter.Result(true, departSec + walkSec, walkSec, 0, List.of(leg));
    }

    /** 여정 식별 키(중복 제거용). 도보만인 여정은 모두 동일 키로 취급(중복 도보 옵션 방지). */
    private String key(RaptorRouter.Result r) {
        boolean walkOnly = r.legs().stream().allMatch(l -> "WALK".equals(l.mode()));
        if (walkOnly) return "WALK-ONLY";
        String route = r.legs().stream().filter(l -> !"WALK".equals(l.mode()))
                .map(RaptorRouter.Leg::routeName).findFirst().orElse("");
        return r.arrivalSec() + "|" + r.transfers() + "|" + route;
    }

    private RaptorRouter.Result minScore(List<RaptorRouter.Result> js) {
        RaptorRouter.Result best = js.get(0);
        int bs = Integer.MAX_VALUE;
        for (RaptorRouter.Result r : js) {
            int sc = router.score(r); // 추천 선정 점수(모드별 환승 페널티 + 지하철·철도 신뢰성 보너스) 통일
            if (sc < bs) { bs = sc; best = r; }
        }
        return best;
    }

    private boolean usesRail(RaptorRouter.Result r) {
        return r.legs().stream().anyMatch(l -> "SUBWAY".equals(l.mode()) || "RAIL".equals(l.mode()));
    }

    private Map<String, Object> oneJourney(Timetable tt, RaptorRouter.Result r, int departSec, String tag, boolean withRealtime) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tag", tag);
        m.put("departure", fmt(departSec));
        m.put("arrival", fmt(r.arrivalSec()));
        m.put("durationMin", Math.round(r.durationSec() / 60.0));
        m.put("transfers", r.transfers());
        m.put("estimatedFareKrw", estimateFare(tt, r));
        m.put("modes", r.legs().stream().map(RaptorRouter.Leg::mode).filter(x -> !"WALK".equals(x)).distinct().toList());
        List<Map<String, Object>> legs = new ArrayList<>();
        for (RaptorRouter.Leg l : r.legs()) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("mode", l.mode());
            if (!"WALK".equals(l.mode())) lm.put("route", l.routeName());
            lm.put("from", l.fromName());
            lm.put("to", l.toName());
            lm.put("depart", fmt(l.depSec()));
            lm.put("arrive", fmt(l.arrSec()));
            lm.put("min", Math.round((l.arrSec() - l.depSec()) / 60.0));
            Integer fi = tt.stopIndex.get(l.fromStopId()), ti = tt.stopIndex.get(l.toStopId());
            if (fi != null) { lm.put("fromLat", tt.lat[fi]); lm.put("fromLon", tt.lon[fi]); }
            if (ti != null) { lm.put("toLat", tt.lat[ti]); lm.put("toLon", tt.lon[ti]); }
            if (l.path() != null && !l.path().isEmpty()) { // 정차 순서대로 경유좌표(지도 선형용)
                List<List<Double>> pts = new ArrayList<>(l.path().size());
                for (double[] pt : l.path()) pts.add(List.of(pt[0], pt[1]));
                lm.put("path", pts);
            }
            legs.add(lm);
        }
        if (withRealtime) enrichRealtime(legs); // 첫 버스 승차 정류장에 실시간 도착 부착(추천 경로만)
        m.put("legs", legs);
        return m;
    }

    /**
     * 첫 대중교통 leg가 버스면 승차 정류장의 실시간 도착을 찾아 해당 노선 ETA를 부착.
     * 서울(도시코드 11)은 서울시 API(arsId), 그 외는 TAGO(nodeId). 0.9초 시간제한.
     */
    private void enrichRealtime(List<Map<String, Object>> legs) {
        if (!tagoStops.isLoaded() || (!tago.isEnabled() && !seoulBus.isEnabled())) return;
        for (Map<String, Object> leg : legs) {
            String mode = (String) leg.get("mode");
            if ("WALK".equals(mode)) continue;      // 접근/환승 도보는 건너뛰고
            if (!"BUS".equals(mode)) return;         // 첫 대중교통이 버스가 아니면(지하철 등) 실시간 생략
            Object fl = leg.get("fromLat"), fo = leg.get("fromLon");
            if (fl == null || fo == null) return;
            String[] tn = tagoStops.nearest(((Number) fl).doubleValue(), ((Number) fo).doubleValue(), 60);
            if (tn == null) return;                  // 근처 정류장 없음
            String nodeId = tn[0], cityCode = tn[1], arsId = tn[2];
            String routeNo = String.valueOf(leg.getOrDefault("route", ""));
            boolean seoul = "11".equals(cityCode);
            if (seoul ? !seoulBus.isEnabled() : !tago.isEnabled()) return;
            // 응답 지연 방지 위해 0.9초 시간제한(초과 시 생략; 백그라운드가 캐시 채워 다음 요청은 빠름).
            List<com.daero.client.BusArrival> arrivals;
            try {
                arrivals = java.util.concurrent.CompletableFuture
                        .supplyAsync(() -> seoul ? seoulBus.arrivals(arsId) : tago.arrivals(cityCode, nodeId))
                        .get(900, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                return;
            }
            for (com.daero.client.BusArrival a : arrivals) {
                if (a.routeNo().equals(routeNo)) {
                    leg.put("realtimeMin", a.etaMin());
                    leg.put("realtimeSec", a.etaSec());
                    leg.put("realtimeStopsLeft", a.stopsLeft());
                    break;
                }
            }
            return; // 첫 버스 leg 하나만
        }
    }

    /**
     * 도시 대중교통(버스·지하철) 구간만 통합요금 근사: 기본 1250원 + 10km 초과 5km마다 100원.
     * 철도·항공 구간은 실요금 체계가 달라 요금 산정에서 제외(거리에 포함하지 않음, fareNote로 별도 안내).
     */
    private int estimateFare(Timetable tt, RaptorRouter.Result r) {
        double km = 0;
        boolean anyUrban = false;
        for (RaptorRouter.Leg l : r.legs()) {
            if (!("BUS".equals(l.mode()) || "SUBWAY".equals(l.mode()))) continue; // 도시 구간만
            Integer f = tt.stopIndex.get(l.fromStopId()), t = tt.stopIndex.get(l.toStopId());
            if (f == null || t == null) continue;
            anyUrban = true;
            km += Timetable.distanceMeters(tt.lat[f], tt.lon[f], tt.lat[t], tt.lon[t]) / 1000.0;
        }
        if (!anyUrban) return 0; // 도시구간 없음(도보만/철도만) → 0
        return 1250 + (int) Math.max(0, Math.ceil((km - 10) / 5.0)) * 100;
    }

    private List<int[]> topAccess(List<int[]> near) {
        near.sort(Comparator.comparingInt(a -> a[1]));
        return near.size() > MAX_ACCESS_STOPS ? new ArrayList<>(near.subList(0, MAX_ACCESS_STOPS)) : near;
    }

    private Map<String, Object> stopMap(Timetable tt, int s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stopId", tt.stopId[s]); m.put("name", tt.stopName[s]);
        m.put("lat", tt.lat[s]); m.put("lon", tt.lon[s]);
        return m;
    }

    private static int parseTime(String s) {
        if (s == null || s.isBlank()) { // 미지정 → 현재 시각(한국 표준시) 기준 출발
            java.time.LocalTime now = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Seoul"));
            return now.getHour() * 3600 + now.getMinute() * 60 + now.getSecond();
        }
        String[] p = s.split(":");
        int h = Integer.parseInt(p[0].trim());
        int m = p.length > 1 ? Integer.parseInt(p[1].trim()) : 0;
        int sec = p.length > 2 ? Integer.parseInt(p[2].trim()) : 0;
        return h * 3600 + m * 60 + sec;
    }

    private static String fmt(int sec) {
        return String.format("%02d:%02d", (sec / 3600) % 24, (sec % 3600) / 60);
    }
}
