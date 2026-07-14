package com.daero.gtfs.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/**
 * GTFS calendar.txt (+ calendar_dates.txt 예외) — serviceId 의 운행 요일/기간.
 * {@link #runsOn(LocalDate)} 로 특정 날짜 운행 여부 판정(예외일 우선).
 */
public record ServiceCalendar(
        String serviceId,
        Set<DayOfWeek> days,
        LocalDate start,
        LocalDate end,
        Set<LocalDate> addedDates,     // calendar_dates exception_type=1 (추가)
        Set<LocalDate> removedDates    // calendar_dates exception_type=2 (제거)
) {
    public boolean runsOn(LocalDate date) {
        if (removedDates.contains(date)) return false;
        if (addedDates.contains(date)) return true;
        if (start != null && date.isBefore(start)) return false;
        if (end != null && date.isAfter(end)) return false;
        return days.contains(date.getDayOfWeek());
    }
}
