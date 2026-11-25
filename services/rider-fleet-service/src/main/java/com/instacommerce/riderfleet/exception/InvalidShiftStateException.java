package com.instacommerce.riderfleet.exception;

import org.springframework.http.HttpStatus;

public class InvalidShiftStateException extends ApiException {
    public InvalidShiftStateException(String message) {
        super(HttpStatus.CONFLICT, "INVALID_SHIFT_STATE", message);
    }
}
