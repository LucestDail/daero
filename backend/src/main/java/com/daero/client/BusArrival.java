package com.daero.client;

/** 실시간 버스 도착 항목(TAGO·서울 공통). etaSec: 도착예정 초. */
public record BusArrival(String routeNo, String routeType, int stopsLeft, int etaSec) {
    public int etaMin() { return (int) Math.round(etaSec / 60.0); }
}
