package com.daero.gtfs.model;

/**
 * GTFS stop_times.txt — trip 이 특정 정류장에 서는 시각.
 * arrivalSec/departureSec: 자정 기준 초(24h 초과 가능 — 심야운행). GTFS "HH:MM:SS" 를 초로 파싱.
 * <p>가장 큰 테이블(전국 수백만 행) — RAPTOR 단계에서 컬럼형 int 배열로 재구성한다.
 */
public record StopTime(String tripId, int seq, String stopId, int arrivalSec, int departureSec) {}
