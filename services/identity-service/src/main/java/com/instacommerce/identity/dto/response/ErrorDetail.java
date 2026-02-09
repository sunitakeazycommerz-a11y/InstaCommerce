package com.instacommerce.identity.dto.response;

public record ErrorDetail(
    String field,
    String message
) {
}
