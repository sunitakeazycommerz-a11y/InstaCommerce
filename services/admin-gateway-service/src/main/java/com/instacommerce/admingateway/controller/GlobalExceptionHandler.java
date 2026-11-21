package com.instacommerce.admingateway.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.platform.web.ProblemDetailFactory;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
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
    public ResponseEntity<ProblemDetail> handleUpstreamHttpStatus(
            HttpStatusCodeException ex,
            HttpServletRequest request) {
        String code = "UPSTREAM_ERROR";
        String detail = ex.getStatusText() != null && !ex.getStatusText().isBlank()
            ? ex.getStatusText()
            : "Downstream request failed";

        String responseBody = ex.getResponseBodyAsString();
        if (responseBody != null && !responseBody.isBlank()) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(responseBody, MAP_TYPE);
                if (parsed.get("code") instanceof String downstreamCode && !downstreamCode.isBlank()) {
                    code = downstreamCode;
                }
                if (parsed.get("detail") instanceof String downstreamDetail && !downstreamDetail.isBlank()) {
                    detail = downstreamDetail;
                } else if (parsed.get("message") instanceof String downstreamMessage && !downstreamMessage.isBlank()) {
                    detail = downstreamMessage;
                }
            } catch (Exception parseEx) {
                log.debug("Downstream error body is not JSON: status={}", ex.getStatusCode().value(), parseEx);
            }
        }
        return ProblemDetailFactory.response(ex.getStatusCode(), code, detail, request);
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<ProblemDetail> handleUpstreamUnavailable(
            RestClientException ex,
            HttpServletRequest request) {
        log.warn("Upstream request failed: {}", ex.getMessage());
        String detail = ex.getMessage() != null ? ex.getMessage() : "Downstream service unavailable";
        return ProblemDetailFactory.response(HttpStatus.BAD_GATEWAY, "UPSTREAM_UNAVAILABLE", detail, request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalArgument(
            IllegalArgumentException ex,
            HttpServletRequest request) {
        String detail = ex.getMessage() != null ? ex.getMessage() : "Invalid request";
        return ProblemDetailFactory.response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", detail, request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(
            AccessDeniedException ex,
            HttpServletRequest request) {
        return ProblemDetailFactory.response(
            HttpStatus.FORBIDDEN,
            "ACCESS_DENIED",
            "Insufficient permissions",
            request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(
            Exception ex,
            HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ProblemDetailFactory.response(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_ERROR",
            "An unexpected error occurred",
            request);
    }
}
