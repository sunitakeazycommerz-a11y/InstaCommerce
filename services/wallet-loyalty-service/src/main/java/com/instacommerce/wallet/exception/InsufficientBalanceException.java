package com.instacommerce.wallet.exception;

import org.springframework.http.HttpStatus;

public class InsufficientBalanceException extends ApiException {
    public InsufficientBalanceException(String message) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "INSUFFICIENT_BALANCE", message);
    }
}
