package com.instacommerce.admingateway.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public GlobalExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @ExceptionHandler(HttpStatusCodeException.class)
    public ResponseEntity<Map<String, Object>> handleUpstreamHttpStatus(HttpStatusCodeException ex) {
        String responseBody = ex.getResponseBodyAsString();
        if (responseBody != null && !responseBody.isBlank()) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(responseBody, MAP_TYPE);
                return ResponseEntity.status(ex.getStatusCode()).body(parsed);
            } catch (Exception ignored) {
                log.debug("Downstream error body is not JSON: status={}", ex.getStatusCode().value());
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", "UPSTREAM_ERROR");
        payload.put("message", ex.getStatusText() != null && !ex.getStatusText().isBlank()
            ? ex.getStatusText()
            : "Downstream request failed");
        payload.put("status", ex.getStatusCode().value());
        payload.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(ex.getStatusCode()).body(payload);
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<Map<String, Object>> handleUpstreamUnavailable(RestClientException ex) {
        log.warn("Upstream request failed: {}", ex.getMessage());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", "UPSTREAM_UNAVAILABLE");
        payload.put("message", ex.getMessage() != null ? ex.getMessage() : "Downstream service unavailable");
        payload.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(payload);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", "INVALID_REQUEST");
        payload.put("message", ex.getMessage());
        payload.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(payload);
    }
}
