package com.instacommerce.mobilebff.exception;

import com.instacommerce.mobilebff.security.MobileBffAccessDeniedHandler;
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
}
