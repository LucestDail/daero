package com.daero.gtfs.model;

/** GTFS transfers.txt — 정류장 간 도보 환승 최소시간(초). 없으면 좌표기반으로 사전계산. */
public record Transfer(String fromStopId, String toStopId, int minTransferSec) {}
