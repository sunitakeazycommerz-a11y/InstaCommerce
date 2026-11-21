package com.instacommerce.catalog.exception;

import com.instacommerce.platform.web.ProblemDetailFactory;
import com.instacommerce.platform.web.ProblemDetailFactory.FieldViolation;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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
        List<FieldViolation> details = ex.getBindingResult().getFieldErrors().stream()
            .map(this::toViolation)
            .toList();
        return ProblemDetailFactory.response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid input", request, details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraint(ConstraintViolationException ex,
                                                          HttpServletRequest request) {
        List<FieldViolation> details = ex.getConstraintViolations().stream()
            .map(violation -> new FieldViolation(violation.getPropertyPath().toString(), violation.getMessage()))
            .toList();
        return ProblemDetailFactory.response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid input", request, details);
    }

    @ExceptionHandler(RequestNotPermitted.class)
    public ResponseEntity<ProblemDetail> handleRateLimit(RequestNotPermitted ex, HttpServletRequest request) {
        return ProblemDetailFactory.response(
            HttpStatus.TOO_MANY_REQUESTS,
            "RATE_LIMIT_EXCEEDED",
            "Too many requests. Try again later.",
            request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return ProblemDetailFactory.response(
            HttpStatus.FORBIDDEN,
            "ACCESS_DENIED",
            "Insufficient permissions",
            request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleReadable(HttpMessageNotReadableException ex,
                                                        HttpServletRequest request) {
        return ProblemDetailFactory.response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid input", request);
    }

    @ExceptionHandler({OptimisticLockException.class, ObjectOptimisticLockingFailureException.class})
    public ResponseEntity<ProblemDetail> handleOptimisticLock(Exception ex, HttpServletRequest request) {
        return ProblemDetailFactory.response(
            HttpStatus.CONFLICT,
            "CONCURRENT_MODIFICATION",
            "Resource was modified concurrently. Please retry.",
            request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException ex,
                                                             HttpServletRequest request) {
        return ProblemDetailFactory.response(
            HttpStatus.CONFLICT,
            "CONFLICT",
            "Resource conflicts with an existing record",
            request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleFallback(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ProblemDetailFactory.response(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_ERROR",
            "An unexpected error occurred",
            request);
    }

    private FieldViolation toViolation(FieldError error) {
        return new FieldViolation(error.getField(), error.getDefaultMessage());
    }
}
