package com.instacommerce.fulfillment.exception;

import org.springframework.http.HttpStatus;

public class InvalidProofOfDeliveryException extends ApiException {
    public InvalidProofOfDeliveryException(String message) {
        super(HttpStatus.BAD_REQUEST, "INVALID_PROOF_OF_DELIVERY", message);
    }
}
