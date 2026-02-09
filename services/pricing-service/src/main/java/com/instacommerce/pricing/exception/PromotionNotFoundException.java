package com.instacommerce.pricing.exception;

import org.springframework.http.HttpStatus;

public class PromotionNotFoundException extends ApiException {
    public PromotionNotFoundException(String promotionId) {
        super(HttpStatus.NOT_FOUND, "PROMOTION_NOT_FOUND",
            "Promotion not found: " + promotionId);
    }
}
