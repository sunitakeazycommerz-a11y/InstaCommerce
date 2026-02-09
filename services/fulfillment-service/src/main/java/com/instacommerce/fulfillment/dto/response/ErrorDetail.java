package com.instacommerce.fulfillment.dto.response;

public record ErrorDetail(
    String field,
    String message
) {
}
