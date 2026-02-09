package com.instacommerce.payment.exception;

import org.springframework.http.HttpStatus;

public class PaymentGatewayException extends ApiException {
    public PaymentGatewayException(String message) {
        super(HttpStatus.INTERNAL_SERVER_ERROR, "PSP_ERROR", message);
    }

    public PaymentGatewayException(String message, Throwable cause) {
        super(HttpStatus.INTERNAL_SERVER_ERROR, "PSP_ERROR", message);
        initCause(cause);
    }
}
