package com.instacommerce.order.dto.response;

public record ErrorDetail(
    String field,
    String message
) {
}
