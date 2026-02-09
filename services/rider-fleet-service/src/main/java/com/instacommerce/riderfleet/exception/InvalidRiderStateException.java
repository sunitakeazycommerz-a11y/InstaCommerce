package com.instacommerce.riderfleet.exception;

import org.springframework.http.HttpStatus;

public class InvalidRiderStateException extends ApiException {
    public InvalidRiderStateException(String message) {
        super(HttpStatus.CONFLICT, "INVALID_RIDER_STATE", message);
    }
}
