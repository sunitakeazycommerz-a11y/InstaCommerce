package com.instacommerce.mobilebff.exception;

import com.instacommerce.mobilebff.security.CorrelationIdWebFilter;
import com.instacommerce.mobilebff.security.MobileBffAccessDeniedHandler;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String PROBLEM_TYPE_PREFIX = "urn:instacommerce:problem:";
    private static final List<String> TRACE_HEADERS = List.of(
        CorrelationIdWebFilter.HEADER_CORRELATION_ID,
        "X-B3-TraceId",
        "X-Trace-Id",
        "traceparent",
        "X-Request-Id");

    public record FieldViolation(String field, String message) {
    }

    private final MobileBffAccessDeniedHandler accessDeniedHandler;

    public GlobalExceptionHandler(MobileBffAccessDeniedHandler accessDeniedHandler) {
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public Mono<Void> handleAccessDenied(AccessDeniedException ex, ServerWebExchange exchange) {
        return accessDeniedHandler.handle(exchange, ex);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalArgument(
            IllegalArgumentException ex,
            ServerWebExchange exchange) {
        String detail = ex.getMessage() != null ? ex.getMessage() : "Invalid request";
        return problemResponse(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", detail, exchange, null);
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ProblemDetail> handleBindException(
            WebExchangeBindException ex,
            ServerWebExchange exchange) {
        List<FieldViolation> errors = ex.getBindingResult().getFieldErrors().stream()
            .map(err -> new FieldViolation(err.getField(), err.getDefaultMessage()))
            .toList();
        return problemResponse(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid input", exchange, errors);
    }

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<ProblemDetail> handleInputException(
            ServerWebInputException ex,
            ServerWebExchange exchange) {
        String detail = ex.getReason() != null ? ex.getReason() : "Invalid request input";
        return problemResponse(ex.getStatusCode(), "VALIDATION_ERROR", detail, exchange, null);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleResponseStatus(
            ResponseStatusException ex,
            ServerWebExchange exchange) {
        String code = "HTTP_" + ex.getStatusCode().value();
        String detail = ex.getReason() != null ? ex.getReason() : "Request failed";
        return problemResponse(ex.getStatusCode(), code, detail, exchange, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleFallback(Exception ex, ServerWebExchange exchange) {
        log.error("Unhandled exception on {} {}",
            exchange.getRequest().getMethod(),
            exchange.getRequest().getPath().value(),
            ex);
        return problemResponse(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_ERROR",
            "An unexpected error occurred",
            exchange,
            null);
    }

    public static ProblemDetail createProblemDetail(
            HttpStatusCode status,
            String code,
            String detail,
            ServerWebExchange exchange,
            List<FieldViolation> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        HttpStatus resolved = HttpStatus.resolve(status.value());
        if (resolved != null) {
            problem.setTitle(resolved.getReasonPhrase());
        }
        if (code != null && !code.isBlank()) {
            String trimmed = code.trim();
            String normalized = trimmed.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-");
            problem.setType(URI.create(PROBLEM_TYPE_PREFIX + normalized));
            problem.setProperty("code", trimmed);
        }
        if (exchange != null) {
            String path = exchange.getRequest().getPath().value();
            if (!path.isBlank()) {
                problem.setInstance(URI.create(path));
            }
            String traceId = resolveTraceId(exchange.getRequest().getHeaders());
            if (traceId != null) {
                problem.setProperty("traceId", traceId);
            }
        }
        problem.setProperty("timestamp", Instant.now().toString());
        if (errors != null && !errors.isEmpty()) {
            problem.setProperty("errors", List.copyOf(errors));
        }
        return problem;
    }

    private static ResponseEntity<ProblemDetail> problemResponse(
            HttpStatusCode status,
            String code,
            String detail,
            ServerWebExchange exchange,
            List<FieldViolation> errors) {
        return ResponseEntity.status(status.value())
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(createProblemDetail(status, code, detail, exchange, errors));
    }

    private static String resolveTraceId(HttpHeaders headers) {
        for (String header : TRACE_HEADERS) {
            String value = headers.getFirst(header);
            if (value != null && !value.isBlank()) {
                String trimmed = value.trim();
                if (trimmed.startsWith("00-")) {
                    String[] parts = trimmed.split("-");
                    if (parts.length > 1 && !parts[1].isBlank()) {
                        return parts[1];
                    }
                }
                return trimmed;
            }
        }
        return null;
    }
}
