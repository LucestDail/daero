package com.daero.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IP당 토큰버킷 레이트리밋(인메모리) — 공개 오픈 API 남용/버스트 방어.
 * 경로탐색 쿼리는 라벨배열 ~40MB를 할당하므로, 무제한 동시요청은 저메모리 인스턴스에서 OOM 위험.
 * nginx가 아닌 앱 계층에 두어 self-host·Docker 등 배포 형태와 무관하게 작동한다.
 *
 * <p>설정(application.properties):
 * daero.ratelimit.enabled(기본 true), .capacity(버스트 토큰, 기본 120), .refill-per-sec(초당 회복, 기본 2).
 */
@Slf4j
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final boolean enabled;
    private final double capacity;
    private final double refillPerSec;
    private static final int MAX_TRACKED_IPS = 50_000; // 스푸핑 IP로 인한 맵 무한증식 방지 상한

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(@Value("${daero.ratelimit.enabled:true}") boolean enabled,
                           @Value("${daero.ratelimit.capacity:120}") double capacity,
                           @Value("${daero.ratelimit.refill-per-sec:2}") double refillPerSec) {
        this.enabled = enabled;
        this.capacity = capacity;
        this.refillPerSec = refillPerSec;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (!enabled || !req.getRequestURI().startsWith("/api/")) {
            chain.doFilter(req, res);
            return;
        }
        if (buckets.size() > MAX_TRACKED_IPS) buckets.clear(); // 상한 초과 시 리셋(보수적)
        Bucket b = buckets.computeIfAbsent(clientIp(req), k -> new Bucket(capacity));
        if (!b.tryConsume(capacity, refillPerSec)) {
            res.setStatus(429);
            res.setContentType("application/json;charset=UTF-8");
            res.setHeader("Retry-After", "1");
            res.getWriter().write("{\"error\":\"rate_limited\",\"message\":\"요청이 너무 많습니다. 잠시 후 다시 시도하세요.\"}");
            return;
        }
        chain.doFilter(req, res);
    }

    /** 리버스 프록시(nginx) 뒤에서는 X-Forwarded-For 첫 IP가 실제 클라이언트. */
    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return req.getRemoteAddr();
    }

    /** 단순 토큰버킷 — 마지막 소비 이후 경과에 비례해 회복. */
    private static final class Bucket {
        private double tokens;
        private long lastNanos = System.nanoTime();

        Bucket(double initial) { this.tokens = initial; }

        synchronized boolean tryConsume(double capacity, double refillPerSec) {
            long now = System.nanoTime();
            double elapsedSec = (now - lastNanos) / 1_000_000_000.0;
            lastNanos = now;
            tokens = Math.min(capacity, tokens + elapsedSec * refillPerSec);
            if (tokens >= 1.0) { tokens -= 1.0; return true; }
            return false;
        }
    }
}
