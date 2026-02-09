package com.instacommerce.pricing.exception;

import org.springframework.http.HttpStatus;

public class PriceRuleNotFoundException extends ApiException {
    public PriceRuleNotFoundException(String productId) {
        super(HttpStatus.NOT_FOUND, "PRICE_RULE_NOT_FOUND",
            "No active price rule found for product " + productId);
    }
}
