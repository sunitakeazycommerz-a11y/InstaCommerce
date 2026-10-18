package com.instacommerce.warehouse.exception;

import org.springframework.http.HttpStatus;

public class ColdChainPackagingViolationException extends ApiException {

    public ColdChainPackagingViolationException(String message) {
        super(HttpStatus.BAD_REQUEST, "COLD_CHAIN_PACKAGING_REQUIRED", message);
    }
}
