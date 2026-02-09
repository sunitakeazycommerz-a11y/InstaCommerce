package com.instacommerce.order.exception;

import org.springframework.http.HttpStatus;

public class InvalidOrderStateException extends ApiException {
    public InvalidOrderStateException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_STATE_TRANSITION", message);
    }
}
