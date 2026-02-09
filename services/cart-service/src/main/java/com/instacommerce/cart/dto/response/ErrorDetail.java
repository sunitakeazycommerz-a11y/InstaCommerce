package com.instacommerce.cart.dto.response;

public record ErrorDetail(
    String field,
    String message
) {
}
