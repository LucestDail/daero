package com.daero.gtfs.model;

/** GTFS stops.txt — 정류장/역. 좌표는 근접 탐색·도보환승에 사용. */
public record Stop(String id, String name, double lat, double lon) {}
