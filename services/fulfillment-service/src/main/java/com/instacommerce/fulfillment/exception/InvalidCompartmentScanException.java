package com.instacommerce.fulfillment.exception;

import org.springframework.http.HttpStatus;

public class InvalidCompartmentScanException extends ApiException {
    public InvalidCompartmentScanException(String message) {
        super(HttpStatus.BAD_REQUEST, "INVALID_COMPARTMENT_SCAN", message);
    }
}
