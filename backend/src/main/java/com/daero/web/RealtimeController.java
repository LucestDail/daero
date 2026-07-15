package com.daero.web;

import com.daero.client.TagoClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 실시간 버스 도착정보(TAGO). 정류소(cityCode+nodeId) 기준 실시간 도착 조회. */
@RestController
@RequiredArgsConstructor
public class RealtimeController {

    private final TagoClient tago;

    @GetMapping("/api/realtime")
    public Map<String, Object> arrivals(@RequestParam String cityCode, @RequestParam String nodeId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", tago.isEnabled());
        m.put("cityCode", cityCode);
        m.put("nodeId", nodeId);
        if (!tago.isEnabled()) {
            m.put("note", "TAGO_KEY 미설정 — 실시간 비활성");
            m.put("arrivals", List.of());
            return m;
        }
        List<Map<String, Object>> arr = new ArrayList<>();
        for (TagoClient.Arrival a : tago.arrivals(cityCode, nodeId)) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("routeNo", a.routeNo());
            o.put("routeType", a.routeType());
            o.put("stopsLeft", a.stopsLeft());
            o.put("etaSec", a.etaSec());
            o.put("etaMin", a.etaMin());
            o.put("stopName", a.stopName());
            arr.add(o);
        }
        arr.sort((x, y) -> Integer.compare((int) x.get("etaSec"), (int) y.get("etaSec")));
        m.put("count", arr.size());
        m.put("arrivals", arr);
        return m;
    }
}
