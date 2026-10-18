package com.instacommerce.fulfillment.exception;

import org.springframework.http.HttpStatus;

public class DuplicateScanException extends ApiException {
    public DuplicateScanException(String message) {
        super(HttpStatus.CONFLICT, "DUPLICATE_SCAN_DETECTED", message);
    }
}
