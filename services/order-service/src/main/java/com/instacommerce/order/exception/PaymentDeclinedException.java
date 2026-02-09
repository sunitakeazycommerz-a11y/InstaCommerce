package com.instacommerce.order.exception;

import org.springframework.http.HttpStatus;

public class PaymentDeclinedException extends ApiException {
    public PaymentDeclinedException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "PAYMENT_DECLINED", message);
    }
}
