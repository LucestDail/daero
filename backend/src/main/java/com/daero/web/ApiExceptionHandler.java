package com.daero.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 공개 API 전역 예외 처리 — 잘못된 요청에 스택 노출 없이 일관된 JSON({error, message})을 반환한다.
 * (예외처리 이전에는 파라미터 누락/형식오류가 HTML 400 또는 500 스택으로 노출됐음)
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String error, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", error);
        m.put("message", message);
        return ResponseEntity.status(status).body(m);
    }

    /** 필수 파라미터 누락 (예: /api/plan/coords 에 fromLat 없음). */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> missingParam(MissingServletRequestParameterException e) {
        return body(HttpStatus.BAD_REQUEST, "missing_parameter",
                "필수 파라미터 누락: " + e.getParameterName());
    }

    /** 파라미터 타입 불일치 (예: fromLat=abc — double 기대). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> typeMismatch(MethodArgumentTypeMismatchException e) {
        return body(HttpStatus.BAD_REQUEST, "invalid_parameter",
                "파라미터 형식 오류: " + e.getName());
    }

    /** 값 검증 실패 (좌표 범위·시각 형식 등 컨트롤러에서 던진 것). */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> illegalArg(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, "invalid_input", e.getMessage());
    }

    /** 라우팅 게이트 대기 초과(서버 혼잡) → 503. */
    @ExceptionHandler(RoutingGate.BusyException.class)
    public ResponseEntity<Map<String, Object>> busy(RoutingGate.BusyException e) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, "server_busy", e.getMessage());
    }

    /** 그 외 — 스택 트레이스는 로그로만, 응답엔 일반 메시지. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> generic(Exception e) {
        log.error("[api] 처리되지 않은 예외", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "서버 내부 오류");
    }
}
