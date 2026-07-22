package com.daero.gtfs;

import com.daero.gtfs.model.GtfsFeed;
import com.daero.gtfs.model.Route;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GtfsLoader 순수 단위 테스트 — 미니 GTFS(test/resources/mini-gtfs)를 로드해
 * 카운트·필드·모드 판정이 정확한지 검증. 네트워크·스냅샷 미사용.
 */
class GtfsLoaderTest {

    private Path miniGtfs() throws Exception {
        return Paths.get(getClass().getResource("/mini-gtfs").toURI());
    }

    private GtfsFeed loadMini() throws Exception {
        GtfsLoader loader = new GtfsLoader(miniGtfs().toString(), "");
        loader.loadOnStartup();
        return loader.getFeed();
    }

    @Test
    void 미니피드_카운트가_정확하다() throws Exception {
        GtfsFeed feed = loadMini();
        assertThat(feed.isEmpty()).isFalse();
        assertThat(feed.stops()).hasSize(7);
        assertThat(feed.routes()).hasSize(2);
        assertThat(feed.trips()).hasSize(2);
        assertThat(feed.stopTimes()).hasSize(6);
        assertThat(feed.transfers()).hasSize(1);
    }

    @Test
    void 정류장_이름_좌표가_파싱된다() throws Exception {
        GtfsFeed feed = loadMini();
        assertThat(feed.stops().get("S1").name()).isEqualTo("A정류장");
        assertThat(feed.stops().get("S1").lat()).isEqualTo(37.5000);
        assertThat(feed.stops().get("S1").lon()).isEqualTo(127.0000);
    }

    @Test
    void route_id_접두어로_모드가_판정된다() throws Exception {
        GtfsFeed feed = loadMini();
        // BR_ = 버스, RR_ + route_type 1 = 지하철(도시철도)
        assertThat(feed.routes().get("BR_001").mode()).isEqualTo(Route.Mode.BUS);
        assertThat(feed.routes().get("RR_001").mode()).isEqualTo(Route.Mode.SUBWAY);
    }

    @Test
    void 경로없는_로더는_빈피드로_기동한다() {
        GtfsLoader loader = new GtfsLoader("/nonexistent/path/gtfs", "");
        loader.loadOnStartup();
        assertThat(loader.getFeed().isEmpty()).isTrue();
    }
}
