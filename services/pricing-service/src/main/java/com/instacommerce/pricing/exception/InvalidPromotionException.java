package com.instacommerce.pricing.exception;

import org.springframework.http.HttpStatus;

public class InvalidPromotionException extends ApiException {
    public InvalidPromotionException(String message) {
        super(HttpStatus.BAD_REQUEST, "INVALID_PROMOTION", message);
    }
}
