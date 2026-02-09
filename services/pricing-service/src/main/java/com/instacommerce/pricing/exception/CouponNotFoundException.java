package com.instacommerce.pricing.exception;

import org.springframework.http.HttpStatus;

public class CouponNotFoundException extends ApiException {
    public CouponNotFoundException(String code) {
        super(HttpStatus.NOT_FOUND, "COUPON_NOT_FOUND",
            "Coupon not found: " + code);
    }
}
