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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API 계약 통합 테스트 — 미니 GTFS로 실제 컨텍스트를 띄워(ApplicationReady 빌드 포함)
 * 엔드포인트 상태코드·응답 필드를 검증. 운영 GTFS/스냅샷은 절대 건드리지 않는다
 * (daero.gtfs.path=미니, daero.timetable.bin= 을 동적 주입).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiContractTest {

    @DynamicPropertySource
    static void miniGtfsProps(DynamicPropertyRegistry reg) throws Exception {
        String mini = Paths.get(ApiContractTest.class.getResource("/mini-gtfs").toURI()).toString();
        reg.add("daero.gtfs.path", () -> mini);
        reg.add("daero.timetable.bin", () -> "");
    }

    @Autowired
    MockMvc mvc;

    @Test
    void stats_는_로드된_정류장수를_반환() throws Exception {
        mvc.perform(get("/api/gtfs/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loaded").value(true))
                .andExpect(jsonPath("$.stops").value(7));
    }

    @Test
    void search_는_이름부분일치_정류장을_반환() throws Exception {
        mvc.perform(get("/api/plan/search").param("q", "역"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].stopId").exists())
                .andExpect(jsonPath("$[0].name").exists())
                .andExpect(jsonPath("$[0].lat").exists());
    }

    @Test
    void coords_는_좌표간_경로를_찾는다() throws Exception {
        mvc.perform(get("/api/plan/coords")
                        .param("fromLat", "37.5000").param("fromLon", "127.0000")
                        .param("toLat", "37.5400").param("toLon", "127.0000")
                        .param("time", "07:50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.options").isArray())
                .andExpect(jsonPath("$.queryMs").exists());
    }

    @Test
    void 필수파라미터_누락시_400_JSON() throws Exception {
        mvc.perform(get("/api/plan/coords"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("missing_parameter"));
    }

    @Test
    void 좌표_형식오류시_400() throws Exception {
        mvc.perform(get("/api/plan/coords")
                        .param("fromLat", "abc").param("fromLon", "127.0")
                        .param("toLat", "37.5").param("toLon", "127.0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_parameter"));
    }

    @Test
    void 좌표_범위초과시_400() throws Exception {
        mvc.perform(get("/api/plan/coords")
                        .param("fromLat", "999").param("fromLon", "127.0")
                        .param("toLat", "37.5").param("toLon", "127.0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_input"));
    }

    @Test
    void 잘못된_시각형식시_400() throws Exception {
        mvc.perform(get("/api/plan/coords")
                        .param("fromLat", "37.5000").param("fromLon", "127.0000")
                        .param("toLat", "37.5400").param("toLon", "127.0000")
                        .param("time", "빠른시각"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_input"));
    }

    @Test
    void 빈검색어시_400() throws Exception {
        mvc.perform(get("/api/plan/search").param("q", "  "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_input"));
    }
}
