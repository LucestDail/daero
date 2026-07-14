package com.daero.web;

import com.daero.gtfs.GtfsLoader;
import com.daero.gtfs.model.GtfsFeed;
import com.daero.raptor.Timetable;
import com.daero.raptor.TimetableBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 로드된 GTFS 피드 / 빌드된 Timetable 상태 확인(검증/헬스). */
@RestController
@RequestMapping("/api/gtfs")
@RequiredArgsConstructor
public class GtfsController {

    private final GtfsLoader loader;
    private final TimetableBuilder builder;

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        GtfsFeed f = loader.getFeed();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loaded", !f.isEmpty());
        m.put("stops", f.stops().size());
        m.put("routes", f.routes().size());
        m.put("trips", f.trips().size());
        m.put("stopTimes", f.stopTimes().size());
        m.put("calendars", f.calendars().size());
        m.put("transfers", f.transfers().size());
        return m;
    }

    /** 컬럼형 Timetable(RAPTOR 입력) 빌드 상태. */
    @GetMapping("/timetable")
    public Map<String, Object> timetable() {
        Timetable t = builder.getTimetable();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("built", t != null);
        m.put("buildMs", builder.getBuildMs());
        if (t != null) {
            m.put("stops", t.nStops);
            m.put("patterns", t.nPatterns);
            m.put("timeEntries", t.arr.length);
            m.put("transferEdges", t.transferTarget.length);
            // 모드별 패턴 분포(지하철/버스/철도 데이터가 실제 들어왔는지 확인)
            Map<String, Integer> byMode = new LinkedHashMap<>();
            for (int p = 0; p < t.nPatterns; p++) {
                String mode = com.daero.gtfs.model.Route.Mode.values()[t.patternMode[p]].name();
                byMode.merge(mode, 1, Integer::sum);
            }
            m.put("patternsByMode", byMode);
        }
        return m;
    }
}
