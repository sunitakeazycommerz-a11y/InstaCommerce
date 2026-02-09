package com.instacommerce.order.exception;

import org.springframework.http.HttpStatus;

public class DuplicateCheckoutException extends ApiException {
    public DuplicateCheckoutException(String idempotencyKey) {
        super(HttpStatus.CONFLICT, "DUPLICATE_CHECKOUT",
            "Checkout already started for key: " + idempotencyKey);
    }
}
