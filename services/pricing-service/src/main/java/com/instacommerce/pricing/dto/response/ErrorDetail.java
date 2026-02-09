package com.instacommerce.pricing.dto.response;

public record ErrorDetail(
    String field,
    String message
) {
}
