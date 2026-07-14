package com.daero.gtfs.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 경량 GTFS CSV 리더. 헤더 기반 컬럼 접근 + 따옴표/이스케이프("") 처리 + BOM 제거.
 * 대용량 stop_times 대비 **행 단위 스트리밍 콜백**(전체 메모리 적재 방지)만 제공.
 */
public final class CsvReader {

    private CsvReader() {}

    /** 각 행을 헤더명→값 Map 으로 콜백. 큰 파일도 한 행씩 처리. */
    public static void forEachRow(BufferedReader reader, Consumer<Map<String, String>> rowConsumer) throws IOException {
        String headerLine = reader.readLine();
        if (headerLine == null) return;
        headerLine = stripBom(headerLine);
        String[] headers = parseLine(headerLine);

        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) continue;
            String[] cols = parseLine(line);
            Map<String, String> row = new HashMap<>(headers.length * 2);
            for (int i = 0; i < headers.length && i < cols.length; i++) {
                row.put(headers[i], cols[i]);
            }
            rowConsumer.accept(row);
        }
    }

    /** RFC4180 유사: 따옴표 필드 + "" 이스케이프 + 필드 내 콤마 허용. */
    static String[] parseLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else inQuotes = false;
                } else {
                    cur.append(c);
                }
            } else {
                if (c == '"') inQuotes = true;
                else if (c == ',') { out.add(cur.toString().trim()); cur.setLength(0); }
                else cur.append(c);
            }
        }
        out.add(cur.toString().trim());
        return out.toArray(new String[0]);
    }

    private static String stripBom(String s) {
        return (!s.isEmpty() && s.charAt(0) == '﻿') ? s.substring(1) : s;
    }
}
