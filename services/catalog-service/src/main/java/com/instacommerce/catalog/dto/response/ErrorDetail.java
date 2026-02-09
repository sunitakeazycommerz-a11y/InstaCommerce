package com.instacommerce.catalog.dto.response;

public record ErrorDetail(
    String field,
    String message
) {
}
