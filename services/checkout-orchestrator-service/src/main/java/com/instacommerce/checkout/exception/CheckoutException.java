package com.instacommerce.checkout.exception;

import com.instacommerce.checkout.dto.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

public class CheckoutException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    private final List<ErrorDetail> details;

    public CheckoutException(String code, String message, HttpStatus status) {
        this(code, message, status, List.of());
    }

    public CheckoutException(String code, String message, HttpStatus status, List<ErrorDetail> details) {
        super(message);
        this.code = code;
        this.status = status;
        this.details = normalizeDetails(details);
    }

    public CheckoutException(String code, String message, HttpStatus status, Throwable cause) {
        this(code, message, status, List.of(), cause);
    }

    public CheckoutException(String code, String message, HttpStatus status, List<ErrorDetail> details, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
        this.details = normalizeDetails(details);
    }

    public String getCode() { return code; }
    public HttpStatus getStatus() { return status; }
    public List<ErrorDetail> getDetails() { return details; }

    private static List<ErrorDetail> normalizeDetails(List<ErrorDetail> details) {
        return details == null ? List.of() : List.copyOf(details);
    }
}
