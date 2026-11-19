package com.instacommerce.mobilebff.exception;

import com.instacommerce.mobilebff.security.MobileBffAccessDeniedHandler;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@ControllerAdvice
public class GlobalExceptionHandler {

    private final MobileBffAccessDeniedHandler accessDeniedHandler;

    public GlobalExceptionHandler(MobileBffAccessDeniedHandler accessDeniedHandler) {
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public Mono<Void> handleAccessDenied(AccessDeniedException ex, ServerWebExchange exchange) {
        return accessDeniedHandler.handle(exchange, ex);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of(
                "code", "VALIDATION_ERROR",
                "message", ex.getMessage() != null ? ex.getMessage() : "Invalid request",
                "timestamp", Instant.now().toString()));
    }
}
