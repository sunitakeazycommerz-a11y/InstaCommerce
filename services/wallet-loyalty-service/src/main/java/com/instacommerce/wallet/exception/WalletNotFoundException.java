package com.instacommerce.wallet.exception;

import org.springframework.http.HttpStatus;

public class WalletNotFoundException extends ApiException {
    public WalletNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", message);
    }
}
