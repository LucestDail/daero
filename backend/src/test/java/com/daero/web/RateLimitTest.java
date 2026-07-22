package com.daero.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 레이트리밋 필터 검증 — capacity=3, refill=0(테스트 창 내 무회복)으로 띄워
 * 같은 IP의 연속 요청이 상한 초과 시 429가 나는지 확인.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RateLimitTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) throws Exception {
        String mini = Paths.get(RateLimitTest.class.getResource("/mini-gtfs").toURI()).toString();
        reg.add("daero.gtfs.path", () -> mini);
        reg.add("daero.timetable.bin", () -> "");
        reg.add("daero.ratelimit.enabled", () -> "true");
        reg.add("daero.ratelimit.capacity", () -> "3");
        reg.add("daero.ratelimit.refill-per-sec", () -> "0");
    }

    @Autowired
    MockMvc mvc;

    @Test
    void 상한_초과요청은_429() throws Exception {
        int ok = 0, limited = 0;
        for (int i = 0; i < 8; i++) {
            int status = mvc.perform(get("/api/gtfs/stats")).andReturn().getResponse().getStatus();
            if (status == 200) ok++;
            else if (status == 429) limited++;
        }
        // capacity 3 → 처음 3건 통과, 이후 429
        assertThat(ok).isEqualTo(3);
        assertThat(limited).isEqualTo(5);
    }
}
