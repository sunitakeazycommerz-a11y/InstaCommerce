package com.instacommerce.routing.dto.response;

public record ErrorDetail(
    String field,
    String message
) {
}
