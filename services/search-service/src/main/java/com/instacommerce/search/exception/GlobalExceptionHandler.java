package com.instacommerce.search.exception;

import com.instacommerce.search.dto.ErrorDetail;
import com.instacommerce.search.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final TraceIdProvider traceIdProvider;

    public GlobalExceptionHandler(TraceIdProvider traceIdProvider) {
        this.traceIdProvider = traceIdProvider;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.getStatus())
            .body(buildError(ex.getCode(), ex.getMessage(), List.of(), request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex,
                                                          HttpServletRequest request) {
        List<ErrorDetail> details = ex.getBindingResult().getFieldErrors().stream()
            .map(this::toDetail)
            .collect(Collectors.toList());
        return ResponseEntity.badRequest()
            .body(buildError("VALIDATION_ERROR", "Invalid input", details, request));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraint(ConstraintViolationException ex,
                                                          HttpServletRequest request) {
        List<ErrorDetail> details = ex.getConstraintViolations().stream()
            .map(violation -> new ErrorDetail(violation.getPropertyPath().toString(), violation.getMessage()))
            .collect(Collectors.toList());
        return ResponseEntity.badRequest()
            .body(buildError("VALIDATION_ERROR", "Invalid input", details, request));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(buildError("ACCESS_DENIED", "Insufficient permissions", List.of(), request));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleReadable(HttpMessageNotReadableException ex,
                                                        HttpServletRequest request) {
        return ResponseEntity.badRequest()
            .body(buildError("VALIDATION_ERROR", "Invalid input", List.of(), request));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleFallback(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(buildError("INTERNAL_ERROR", "An unexpected error occurred", List.of(), request));
    }

    private ErrorDetail toDetail(FieldError error) {
        return new ErrorDetail(error.getField(), error.getDefaultMessage());
    }

    private ErrorResponse buildError(String code, String message, List<ErrorDetail> details,
                                     HttpServletRequest request) {
        return new ErrorResponse(code, message, traceIdProvider.resolveTraceId(request), Instant.now(), details);
    }
}
