package com.instacommerce.inventory.dto.response;

public record ErrorDetail(
    String field,
    String message
) {
}
