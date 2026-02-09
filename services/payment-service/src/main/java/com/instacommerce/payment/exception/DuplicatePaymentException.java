package com.instacommerce.payment.exception;

import org.springframework.http.HttpStatus;

public class DuplicatePaymentException extends ApiException {
    public DuplicatePaymentException(String idempotencyKey) {
        super(HttpStatus.CONFLICT, "DUPLICATE_PAYMENT",
            "Payment already exists for idempotency key: " + idempotencyKey);
    }
}
