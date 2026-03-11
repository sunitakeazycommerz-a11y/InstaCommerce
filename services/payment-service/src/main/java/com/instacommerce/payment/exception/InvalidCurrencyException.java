package com.instacommerce.payment.exception;

import org.springframework.http.HttpStatus;

public class InvalidCurrencyException extends ApiException {
    public InvalidCurrencyException(String currency) {
        super(HttpStatus.BAD_REQUEST, "INVALID_CURRENCY",
            "Unsupported currency: " + currency);
    }
}
