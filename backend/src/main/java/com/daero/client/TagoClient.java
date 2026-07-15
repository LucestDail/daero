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

/**
 * TAGO(국가대중교통정보센터) 버스도착정보 조회 — 정류소별 실시간 도착예정.
 * data.go.kr 게이트웨이가 User-Agent 없으면 403 → UA 필수. 갱신 10~20초라 15초 캐시.
 * 인증키는 환경변수 TAGO_KEY(디코딩된 원본)로만 주입(코드/깃에 미포함).
 */
@Slf4j
@Component
public class TagoClient {

    private static final String BASE =
            "http://apis.data.go.kr/1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList";
    private static final String UA = "Mozilla/5.0 (compatible; daero/1.0; +https://daero.duckdns.org)";
    private static final long TTL_MS = 15_000;

    private final String serviceKey;
    private final ObjectMapper om;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public TagoClient(@Value("${tago.service-key:}") String serviceKey, ObjectMapper om) {
        this.serviceKey = serviceKey == null ? "" : serviceKey.trim();
        this.om = om;
    }

    public boolean isEnabled() {
        return !serviceKey.isBlank();
    }

    /** 실시간 도착 항목. */
    public record Arrival(String routeNo, String routeType, int stopsLeft, int etaSec,
                          String routeId, String stopName) {
        public int etaMin() { return (int) Math.round(etaSec / 60.0); }
    }

    /** 정류소(cityCode, nodeId)의 실시간 도착 목록. 미설정/실패 시 빈 목록(캐시 있으면 캐시). */
    public List<Arrival> arrivals(String cityCode, String nodeId) {
        if (!isEnabled() || cityCode == null || nodeId == null) return List.of();
        String key = cityCode + "|" + nodeId;
        long now = System.currentTimeMillis();
        Cached c = cache.get(key);
        if (c != null && now - c.ts < TTL_MS) return c.list;
        try {
            String url = BASE + "?serviceKey=" + enc(serviceKey)
                    + "&cityCode=" + enc(cityCode) + "&nodeId=" + enc(nodeId)
                    + "&numOfRows=50&pageNo=1&_type=json";
            List<Arrival> list = parse(get(url));
            cache.put(key, new Cached(now, list));
            return list;
        } catch (Exception e) {
            log.warn("[tago] 도착조회 실패 city={} node={}: {}", cityCode, nodeId, e.getMessage());
            return c != null ? c.list : List.of();
        }
    }

    private List<Arrival> parse(String body) throws Exception {
        List<Arrival> out = new ArrayList<>();
        JsonNode root = om.readTree(body);
        JsonNode header = root.path("response").path("header");
        if (!"00".equals(header.path("resultCode").asText())) return out; // 정상 아님(도착정보 없음 등)
        JsonNode item = root.path("response").path("body").path("items").path("item");
        if (item.isMissingNode() || item.isNull()) return out;
        if (item.isArray()) { for (JsonNode n : item) out.add(toArrival(n)); }
        else out.add(toArrival(item)); // 1건이면 객체
        return out;
    }

    private Arrival toArrival(JsonNode n) {
        return new Arrival(
                n.path("routeno").asText(""),
                n.path("routetp").asText(""),
                n.path("arrprevstationcnt").asInt(0),
                n.path("arrtime").asInt(0),
                n.path("routeid").asText(""),
                n.path("nodenm").asText(""));
    }

    private String get(String apiUrl) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(apiUrl).openConnection();
        try {
            con.setRequestMethod("GET");
            con.setRequestProperty("User-Agent", UA); // data.go.kr WAF 통과 필수
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

    private record Cached(long ts, List<Arrival> list) {}
}
