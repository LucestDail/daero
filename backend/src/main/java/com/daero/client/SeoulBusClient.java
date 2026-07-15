package com.daero.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 서울시 버스도착정보(ws.bus.go.kr getStationByUid) — 정류소고유번호(arsId) 기준 실시간 도착.
 * TAGO가 커버 못 하는 서울(도시코드 11) 전용. 인증키는 환경변수 SEOUL_BUS_KEY(디코딩 원본).
 */
@Slf4j
@Component
public class SeoulBusClient {

    private static final String BASE = "http://ws.bus.go.kr/api/rest/stationinfo/getStationByUid";
    private static final long TTL_MS = 15_000;
    // "3분5초후[2번째 전]", "5분후", "30초후", "곧 도착", "운행종료" 등에서 시간 추출
    private static final Pattern MIN = Pattern.compile("(\\d+)\\s*분");
    private static final Pattern SEC = Pattern.compile("(\\d+)\\s*초");

    private final String serviceKey;
    private final ObjectMapper om;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public SeoulBusClient(@Value("${seoul.bus.service-key:}") String serviceKey, ObjectMapper om) {
        this.serviceKey = serviceKey == null ? "" : serviceKey.trim();
        this.om = om;
    }

    public boolean isEnabled() { return !serviceKey.isBlank(); }

    /** arsId(정류소고유번호) 정류소의 실시간 도착 목록. */
    public List<BusArrival> arrivals(String arsId) {
        if (!isEnabled() || arsId == null || arsId.isBlank()) return List.of();
        long now = System.currentTimeMillis();
        Cached c = cache.get(arsId);
        if (c != null && now - c.ts < TTL_MS) return c.list;
        try {
            String url = BASE + "?serviceKey=" + enc(serviceKey) + "&arsId=" + enc(arsId) + "&resultType=json";
            List<BusArrival> list = parse(get(url));
            cache.put(arsId, new Cached(now, list));
            return list;
        } catch (Exception e) {
            log.warn("[seoul-bus] 도착조회 실패 arsId={}: {}", arsId, e.getMessage());
            return c != null ? c.list : List.of();
        }
    }

    private List<BusArrival> parse(String body) throws Exception {
        List<BusArrival> out = new ArrayList<>();
        JsonNode root = om.readTree(body);
        JsonNode items = root.path("msgBody").path("itemList");
        if (items.isMissingNode() || items.isNull()) return out;
        List<JsonNode> list = new ArrayList<>();
        if (items.isArray()) items.forEach(list::add); else list.add(items);
        for (JsonNode n : list) {
            String rt = n.path("rtNm").asText("");            // 노선명(번호)
            String msg = n.path("arrmsg1").asText("");        // "3분5초후[2번째 전]"
            int eta = parseEta(msg);
            if (eta < 0) continue;                            // 운행종료/출발대기 등 → 제외
            int stops = extractStops(msg);
            out.add(new BusArrival(rt, busType(n.path("busType1").asText("")), stops, eta));
        }
        return out;
    }

    /** arrmsg1 → 도착예정 초. "곧 도착"=0, "운행종료/출발대기/-"=-1(제외). */
    private int parseEta(String msg) {
        if (msg == null || msg.isBlank()) return -1;
        if (msg.contains("곧 도착") || msg.contains("곧도착")) return 0;
        if (msg.contains("운행종료") || msg.contains("출발대기")) return -1;
        int sec = 0; boolean found = false;
        Matcher m = MIN.matcher(msg); if (m.find()) { sec += Integer.parseInt(m.group(1)) * 60; found = true; }
        Matcher s = SEC.matcher(msg); if (s.find()) { sec += Integer.parseInt(s.group(1)); found = true; }
        return found ? sec : -1;
    }

    private int extractStops(String msg) {
        Matcher m = Pattern.compile("\\[(\\d+)번째").matcher(msg);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private String busType(String code) {
        return switch (code) {
            case "1" -> "공항버스"; case "3" -> "간선버스"; case "4" -> "지선버스";
            case "5" -> "순환버스"; case "6" -> "광역버스"; case "7" -> "인천버스"; case "8" -> "경기버스";
            default -> "버스";
        };
    }

    private String get(String apiUrl) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(apiUrl).openConnection();
        try {
            con.setRequestMethod("GET");
            con.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; daero/1.0)");
            con.setRequestProperty("Accept", "application/json");
            con.setConnectTimeout(1500);
            con.setReadTimeout(2500);
            int code = con.getResponseCode();
            var is = code == 200 ? con.getInputStream() : con.getErrorStream();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                if (code != 200) throw new Exception("HTTP " + code);
                return sb.toString();
            }
        } finally {
            con.disconnect();
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private record Cached(long ts, List<BusArrival> list) {}
}
