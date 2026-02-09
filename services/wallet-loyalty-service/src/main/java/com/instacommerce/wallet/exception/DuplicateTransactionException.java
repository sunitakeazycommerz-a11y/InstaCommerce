package com.instacommerce.wallet.exception;

import org.springframework.http.HttpStatus;

public class DuplicateTransactionException extends ApiException {
    public DuplicateTransactionException(String message) {
        super(HttpStatus.CONFLICT, "DUPLICATE_TRANSACTION", message);
    }
}
