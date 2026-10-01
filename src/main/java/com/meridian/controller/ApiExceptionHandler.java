package com.meridian.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Objects;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> domainError(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of(
            "ok", false,
            "error", Objects.requireNonNullElse(e.getReason(), "Request failed"),
            "code", "HTTP_" + e.getStatusCode().value()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> jsonError() {
        return ResponseEntity.badRequest().body(Map.of(
            "ok", false,
            "error", "Invalid JSON request: integer amounts and known fields required",
            "code", "INVALID_JSON"));
    }
}
