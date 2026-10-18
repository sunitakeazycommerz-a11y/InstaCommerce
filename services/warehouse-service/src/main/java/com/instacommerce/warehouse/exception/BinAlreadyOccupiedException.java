package com.instacommerce.warehouse.exception;

import org.springframework.http.HttpStatus;

public class BinAlreadyOccupiedException extends ApiException {

    public BinAlreadyOccupiedException(String message) {
        super(HttpStatus.CONFLICT, "BIN_ALREADY_OCCUPIED", message);
    }
}
