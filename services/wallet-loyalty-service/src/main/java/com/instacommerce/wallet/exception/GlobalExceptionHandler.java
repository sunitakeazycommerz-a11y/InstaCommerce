package com.instacommerce.wallet.exception;

import com.instacommerce.platform.web.ProblemDetailFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException ex, HttpServletRequest request) {
        return ProblemDetailFactory.response(ex.getStatus(), ex.getCode(), ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex,
                                                          HttpServletRequest request) {
        List<ProblemDetailFactory.FieldViolation> details = ex.getBindingResult().getFieldErrors().stream()
            .map(this::toDetail)
            .toList();
        return ProblemDetailFactory.response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid input", request, details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraint(ConstraintViolationException ex,
                                                          HttpServletRequest request) {
        List<ProblemDetailFactory.FieldViolation> details = ex.getConstraintViolations().stream()
            .map(violation -> new ProblemDetailFactory.FieldViolation(
                violation.getPropertyPath().toString(), violation.getMessage()))
            .toList();
        return ProblemDetailFactory.response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid input", request, details);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return ProblemDetailFactory.response(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Insufficient permissions", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleReadable(HttpMessageNotReadableException ex,
                                                        HttpServletRequest request) {
        return ProblemDetailFactory.response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid input", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException ex,
                                                               HttpServletRequest request) {
        return ProblemDetailFactory.response(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", ex.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleFallback(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ProblemDetailFactory.response(
            HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred", request);
    }

    private ProblemDetailFactory.FieldViolation toDetail(FieldError error) {
        return new ProblemDetailFactory.FieldViolation(error.getField(), error.getDefaultMessage());
    }
}
