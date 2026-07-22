package com.daero.raptor;

import com.daero.gtfs.GtfsLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAPTOR 엔진 결정적 회귀 테스트 — 미니 GTFS로 Timetable을 빌드해
 * 알려진 소형 노선의 도착시각·환승·무경로가 기대대로인지 검증.
 *
 * <p>미니망: 버스 BR_001 (S1 08:00 → S2 08:05 → S3 08:10),
 * 지하철 RR_001 (S4 08:15 → S5 08:20 → S6 08:25), 환승 S3↔S4(90초, 좌표 ~11m 근접),
 * S7은 고립 정류장(무경로). 서비스 캘린더는 라우팅에서 필터하지 않으므로 날짜 독립적.
 */
class RaptorRouterTest {

    private Timetable tt;
    private RaptorRouter router;

    private static final int DEPART_0750 = 7 * 3600 + 50 * 60; // 07:50

    @BeforeEach
    void setup() throws Exception {
        String mini = Paths.get(getClass().getResource("/mini-gtfs").toURI()).toString();
        GtfsLoader loader = new GtfsLoader(mini, "");
        loader.loadOnStartup();
        TimetableBuilder builder = new TimetableBuilder(loader, "");
        builder.build();
        tt = builder.getTimetable();
        router = new RaptorRouter(builder);
    }

    private int idx(String stopId) {
        Integer i = tt.stopIndex.get(stopId);
        assertThat(i).as("정류장 %s 인덱스", stopId).isNotNull();
        return i;
    }

    @Test
    void 직행_버스경로_도착시각과_무환승() {
        RaptorRouter.Result r = router.query(idx("S1"), idx("S3"), DEPART_0750);
        assertThat(r.found()).isTrue();
        assertThat(r.arrivalSec()).isEqualTo(8 * 3600 + 10 * 60); // 08:10
        assertThat(r.transfers()).isEqualTo(0);
    }

    @Test
    void 버스_환승_지하철_경로() {
        // S1 → (버스) → S3 → (환승 90초) → S4 → (지하철) → S6, 08:25 도착, 환승 1회
        RaptorRouter.Result r = router.query(idx("S1"), idx("S6"), DEPART_0750);
        assertThat(r.found()).isTrue();
        assertThat(r.arrivalSec()).isEqualTo(8 * 3600 + 25 * 60); // 08:25
        assertThat(r.transfers()).isEqualTo(1);
        assertThat(r.legs()).isNotEmpty();
    }

    @Test
    void 고립_정류장은_무경로() {
        RaptorRouter.Result r = router.query(idx("S1"), idx("S7"), DEPART_0750);
        assertThat(r.found()).isFalse();
    }

    @Test
    void 막차_이후_출발은_무경로() {
        // 09:00 출발 — 모든 trip(08:25까지)이 지난 뒤라 경로 없음
        RaptorRouter.Result r = router.query(idx("S1"), idx("S3"), 9 * 3600);
        assertThat(r.found()).isFalse();
    }
}
