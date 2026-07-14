package com.daero;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * daero (대로) — 열린 대중교통 경로 엔진.
 * GTFS 를 먹여 자체 RAPTOR 로 경로탐색하는 API 서버.
 */
@SpringBootApplication
public class DaeroApplication {
    public static void main(String[] args) {
        SpringApplication.run(DaeroApplication.class, args);
    }
}
