package com.instacommerce.payment.exception;

import org.springframework.http.HttpStatus;

public class PaymentDeclinedException extends ApiException {
    public PaymentDeclinedException(String reason) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "PAYMENT_DECLINED",
            reason == null || reason.isBlank() ? "Payment was declined" : reason);
    }
}
