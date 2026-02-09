package com.instacommerce.order.exception;

import org.springframework.http.HttpStatus;

public class CheckoutFailedException extends ApiException {
    public CheckoutFailedException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "CHECKOUT_FAILED", message);
    }
}
