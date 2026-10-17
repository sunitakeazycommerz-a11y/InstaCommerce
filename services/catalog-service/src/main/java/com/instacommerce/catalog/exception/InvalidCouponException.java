package com.instacommerce.catalog.exception;

import org.springframework.http.HttpStatus;

public class InvalidCouponException extends ApiException {
    public InvalidCouponException(String message) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_COUPON", message);
    }
}
