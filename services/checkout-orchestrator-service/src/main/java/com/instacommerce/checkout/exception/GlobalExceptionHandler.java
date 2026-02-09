package com.instacommerce.checkout.exception;

import com.instacommerce.checkout.dto.ErrorDetail;
import com.instacommerce.checkout.dto.ErrorResponse;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowNotFoundException;
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
import org.springframework.web.client.ResourceAccessException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final TraceIdProvider traceIdProvider;

    public GlobalExceptionHandler(TraceIdProvider traceIdProvider) {
        this.traceIdProvider = traceIdProvider;
    }

    @ExceptionHandler(CheckoutException.class)
    public ResponseEntity<ErrorResponse> handleCheckoutException(CheckoutException ex, HttpServletRequest request) {
        log.warn("Checkout error: code={} message={}", ex.getCode(), ex.getMessage());
        return ResponseEntity.status(ex.getStatus())
            .body(buildError(ex.getCode(), ex.getMessage(), List.of(), request));
    }

    @ExceptionHandler(WorkflowNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleWorkflowNotFound(WorkflowNotFoundException ex,
                                                                 HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(buildError("WORKFLOW_NOT_FOUND", "Checkout workflow not found", List.of(), request));
    }

    @ExceptionHandler(WorkflowException.class)
    public ResponseEntity<ErrorResponse> handleWorkflowException(WorkflowException ex, HttpServletRequest request) {
        log.error("Temporal workflow failure", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(buildError("CHECKOUT_WORKFLOW_FAILED",
                "Checkout failed: " + extractRootCause(ex), List.of(), request));
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ErrorResponse> handleDownstreamTimeout(ResourceAccessException ex,
                                                                  HttpServletRequest request) {
        log.error("Downstream service unavailable", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(buildError("DOWNSTREAM_UNAVAILABLE",
                "A required service is temporarily unavailable. Please retry.", List.of(), request));
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
            .map(v -> new ErrorDetail(v.getPropertyPath().toString(), v.getMessage()))
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
            .body(buildError("VALIDATION_ERROR", "Invalid request body", List.of(), request));
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

    private String extractRootCause(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
    }
}
