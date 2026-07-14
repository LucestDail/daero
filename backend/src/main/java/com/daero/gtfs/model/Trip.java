package com.daero.gtfs.model;

/** GTFS trips.txt — 노선의 1회 운행. serviceId 로 운행일(calendar) 판정. */
public record Trip(String id, String routeId, String serviceId, String headsign) {}
