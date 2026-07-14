package com.daero.gtfs.model;

import java.util.List;
import java.util.Map;

/**
 * 로드된 GTFS 피드(원본 도메인 모델) 컨테이너.
 * RAPTOR 는 이걸 컬럼형 Timetable 로 변환해 사용한다(M2).
 */
public record GtfsFeed(
        Map<String, Stop> stops,
        Map<String, Route> routes,
        Map<String, Trip> trips,
        List<StopTime> stopTimes,
        Map<String, ServiceCalendar> calendars,
        List<Transfer> transfers
) {
    public static GtfsFeed empty() {
        return new GtfsFeed(Map.of(), Map.of(), Map.of(), List.of(), Map.of(), List.of());
    }

    public boolean isEmpty() {
        return stops.isEmpty() && stopTimes.isEmpty();
    }
}
