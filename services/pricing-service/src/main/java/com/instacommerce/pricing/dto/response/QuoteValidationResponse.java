package com.instacommerce.pricing.dto.response;

public record QuoteValidationResponse(
    boolean valid,
    String reason
) {
}
