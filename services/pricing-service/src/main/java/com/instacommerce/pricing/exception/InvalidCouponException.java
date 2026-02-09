package com.instacommerce.pricing.exception;

import org.springframework.http.HttpStatus;

public class InvalidCouponException extends ApiException {
    public InvalidCouponException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_COUPON", message);
    }
}
