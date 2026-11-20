package com.instacommerce.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * Canonical RFC 7807 {@link ProblemDetail} builder and trace-context propagation helper.
 */
public final class ProblemDetailFactory {
    public static final String PROBLEM_TYPE_PREFIX = "urn:instacommerce:problem:";

    private static final List<String> TRACE_HEADERS = List.of(
            "X-B3-TraceId", "X-Trace-Id", "traceparent", "X-Request-Id");

    public record FieldViolation(String field, String message) {
    }

    private ProblemDetailFactory() {
    }

    public static ProblemDetail create(HttpStatusCode status, String code, String detail) {
        return create(status, code, null, detail, null, null, List.of());
    }

    public static ProblemDetail create(
            HttpStatusCode status,
            String code,
            String detail,
            HttpServletRequest request) {
        return create(status, code, null, detail, null, request, List.of());
    }

    public static ProblemDetail create(
            HttpStatusCode status,
            String code,
            String detail,
            HttpServletRequest request,
            List<FieldViolation> errors) {
        return create(status, code, null, detail, null, request, errors);
    }

    public static ProblemDetail create(
            HttpStatusCode status,
            String code,
            String title,
            String detail,
            URI type,
            HttpServletRequest request,
            List<FieldViolation> errors) {
        Objects.requireNonNull(status, "status must not be null");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        String resolvedTitle = (title != null && !title.isBlank()) ? title : defaultTitle(status, code);
        if (resolvedTitle != null) {
            problem.setTitle(resolvedTitle);
        }
        URI resolvedType = type != null ? type : typeForCode(code);
        if (resolvedType != null) {
            problem.setType(resolvedType);
        }
        URI instance = resolveInstanceUri(request);
        if (instance != null) {
            problem.setInstance(instance);
        }
        if (code != null && !code.isBlank()) {
            problem.setProperty("code", code.trim());
        }
        problem.setProperty("timestamp", Instant.now().toString());
        String traceId = resolveTraceId(request);
        if (traceId != null) {
            problem.setProperty("traceId", traceId);
        }
        if (errors != null && !errors.isEmpty()) {
            problem.setProperty("errors", List.copyOf(errors));
        }
        return problem;
    }

    public static ResponseEntity<ProblemDetail> response(
            HttpStatusCode status,
            String code,
            String detail,
            HttpServletRequest request) {
        return toResponseEntity(create(status, code, detail, request));
    }

    public static ResponseEntity<ProblemDetail> response(
            HttpStatusCode status,
            String code,
            String detail,
            HttpServletRequest request,
            List<FieldViolation> errors) {
        return toResponseEntity(create(status, code, detail, request, errors));
    }

    public static ResponseEntity<ProblemDetail> toResponseEntity(ProblemDetail problem) {
        Objects.requireNonNull(problem, "problem must not be null");
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    public static String resolveTraceId(HttpServletRequest request) {
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isBlank()) {
            traceId = firstNonBlankHeader(request);
        }
        if (traceId == null || traceId.isBlank()) {
            return null;
        }
        String trimmed = traceId.trim();
        if (trimmed.startsWith("00-")) {
            String[] parts = trimmed.split("-");
            if (parts.length > 1 && !parts[1].isBlank()) {
                return parts[1];
            }
        }
        return trimmed;
    }

    private static String firstNonBlankHeader(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        for (String header : TRACE_HEADERS) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static URI typeForCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-");
        return URI.create(PROBLEM_TYPE_PREFIX + normalized);
    }

    private static String defaultTitle(HttpStatusCode status, String code) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        if (resolved != null) {
            return resolved.getReasonPhrase();
        }
        return (code != null && !code.isBlank()) ? code.trim() : null;
    }

    private static URI resolveInstanceUri(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String uri = request.getRequestURI();
        if (uri == null || uri.isBlank()) {
            return null;
        }
        try {
            return URI.create(uri.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
