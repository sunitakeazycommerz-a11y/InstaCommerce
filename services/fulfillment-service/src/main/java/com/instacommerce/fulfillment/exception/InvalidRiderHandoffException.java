package com.instacommerce.fulfillment.exception;

import org.springframework.http.HttpStatus;

public class InvalidRiderHandoffException extends ApiException {
    public InvalidRiderHandoffException(String message) {
        super(HttpStatus.FORBIDDEN, "INVALID_RIDER_HANDOFF", message);
    }
}
