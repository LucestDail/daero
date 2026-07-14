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
                                  @RequestParam(defaultValue = "08:00") String time) {
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
        return ResponseEntity.ok(renderTagged(tt, normal, rail, departSec, (System.nanoTime() - t0) / 1_000_000));
    }

    /** 좌표 → 좌표 door-to-door(접근·하차 도보 포함). */
    @GetMapping("/coords")
    public ResponseEntity<?> coords(@RequestParam double fromLat, @RequestParam double fromLon,
                                    @RequestParam double toLat, @RequestParam double toLon,
                                    @RequestParam(defaultValue = "08:00") String time) {
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
        Map<String, Object> m = renderTagged(tt, normal, rail, departSec, (System.nanoTime() - t0) / 1_000_000);
        m.put("accessStops", src.size());
        m.put("egressStops", dst.size());
        return ResponseEntity.ok(m);
    }

    // ── 렌더링/유틸 ─────────────────────────────────────────
    /**
     * 태그된 경로 옵션 구성: 추천(환승 페널티 최소) + 지하철·철도 우선(rail) + 최소환승/빠른 대안.
     * normal/rail 은 각각 도착 이른 순 파레토.
     */
    private Map<String, Object> renderTagged(Timetable tt, List<RaptorRouter.Result> normal,
                                             List<RaptorRouter.Result> rail, int departSec, long queryMs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("queryMs", queryMs);
        if (normal.isEmpty()) { m.put("found", false); return m; }

        RaptorRouter.Result primary = minScore(normal);
        List<Map<String, Object>> opts = new ArrayList<>();
        opts.add(oneJourney(tt, primary, departSec, "추천"));

        // 지하철·철도 우선: rail 파레토 중 최소환승·빠름, 실제 지하철/철도 포함 + 추천과 다를 때
        RaptorRouter.Result railBest = rail.isEmpty() ? null : minScore(rail);
        if (railBest != null && usesRail(railBest) && !sameJourney(railBest, primary)) {
            opts.add(oneJourney(tt, railBest, departSec, "지하철·철도 우선"));
        }
        // 최소 환승 대안(파레토 첫 항목 = 환승 최소) 이 추천과 다르면
        RaptorRouter.Result fewest = normal.get(0);
        if (!sameJourney(fewest, primary) && opts.size() < 3
                && opts.stream().noneMatch(o -> ((int) o.get("transfers")) == fewest.transfers()
                    && o.get("arrival").equals(fmt(fewest.arrivalSec())))) {
            opts.add(oneJourney(tt, fewest, departSec, "환승 최소"));
        }

        m.put("found", true);
        m.putAll(opts.get(0)); // 최상위(추천)을 루트에 펼침(하위호환)
        m.put("options", opts);
        return m;
    }

    private RaptorRouter.Result minScore(List<RaptorRouter.Result> js) {
        RaptorRouter.Result best = js.get(0);
        int bs = Integer.MAX_VALUE;
        for (RaptorRouter.Result r : js) {
            int sc = r.arrivalSec() + 300 * r.transfers();
            if (sc < bs) { bs = sc; best = r; }
        }
        return best;
    }

    private boolean usesRail(RaptorRouter.Result r) {
        return r.legs().stream().anyMatch(l -> "SUBWAY".equals(l.mode()) || "RAIL".equals(l.mode()));
    }

    private boolean sameJourney(RaptorRouter.Result a, RaptorRouter.Result b) {
        return a.arrivalSec() == b.arrivalSec() && a.transfers() == b.transfers();
    }

    private Map<String, Object> oneJourney(Timetable tt, RaptorRouter.Result r, int departSec, String tag) {
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
            legs.add(lm);
        }
        m.put("legs", legs);
        return m;
    }

    /** 수도권 통합요금 근사: 기본 1250원 + 10km 초과 5km마다 100원(도시 대중교통 구간만). */
    private int estimateFare(Timetable tt, RaptorRouter.Result r) {
        double km = 0;
        boolean anyUrban = false;
        for (RaptorRouter.Leg l : r.legs()) {
            if ("WALK".equals(l.mode())) continue;
            Integer f = tt.stopIndex.get(l.fromStopId()), t = tt.stopIndex.get(l.toStopId());
            if (f == null || t == null) continue;
            if ("BUS".equals(l.mode()) || "SUBWAY".equals(l.mode())) anyUrban = true;
            km += Timetable.distanceMeters(tt.lat[f], tt.lon[f], tt.lat[t], tt.lon[t]) / 1000.0;
        }
        if (!anyUrban) return 0; // 철도·항공만이면 근사 부정확 → 0(별도 표기)
        int fare = 1250 + (int) Math.max(0, Math.ceil((km - 10) / 5.0)) * 100;
        return fare;
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
