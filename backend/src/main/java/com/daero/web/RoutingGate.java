package com.daero.web;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 무거운 경로탐색(RAPTOR)의 동시 실행 수를 CPU에 맞춰 제한하는 게이트.
 *
 * <p>배경: 경로 쿼리는 CPU 바운드 + 쿼리당 ~40MB 라벨배열. 2vCPU에서 수십 요청이 동시에
 * 몰리면 (1) 코어 스래싱으로 개별 쿼리가 되레 느려지고 (2) 메모리 피크가 치솟아 OOM 위험.
 * 동시 실행을 코어 배수로 묶으면 각 쿼리가 온전한 CPU를 받아 빨리 끝나고(대기는 짧게 큐잉),
 * 라벨배열 피크도 permit 수로 상한이 걸린다. 경량 엔드포인트(검색·통계)는 게이트를 거치지 않음.
 *
 * <p>설정: daero.routing.max-concurrent(기본 0 → availableProcessors×2, 최소 2),
 * daero.routing.acquire-timeout-ms(기본 8000, 초과 시 503).
 */
@Slf4j
@Component
public class RoutingGate {

    private final int configured;
    private final long timeoutMs;
    private Semaphore semaphore;

    public RoutingGate(@Value("${daero.routing.max-concurrent:0}") int configured,
                       @Value("${daero.routing.acquire-timeout-ms:8000}") long timeoutMs) {
        this.configured = configured;
        this.timeoutMs = timeoutMs;
    }

    @PostConstruct
    void init() {
        int permits = configured > 0 ? configured : Math.max(2, Runtime.getRuntime().availableProcessors() * 2);
        this.semaphore = new Semaphore(permits, true); // fair → 대기 요청 순서 보장
        log.info("[routing-gate] 동시 경로탐색 상한 {} (timeout {}ms)", permits, timeoutMs);
    }

    /** 경로탐색 작업을 게이트 안에서 실행. 대기 초과 시 IllegalStateException(→ 503 매핑). */
    public <T> T run(java.util.function.Supplier<T> work) {
        boolean acquired = false;
        try {
            acquired = semaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
            if (!acquired) throw new BusyException();
            return work.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusyException();
        } finally {
            if (acquired) semaphore.release();
        }
    }

    /** 게이트 대기 초과(서버 혼잡) — 503 으로 매핑. */
    public static class BusyException extends RuntimeException {
        public BusyException() { super("서버가 혼잡합니다. 잠시 후 다시 시도하세요."); }
    }
}
