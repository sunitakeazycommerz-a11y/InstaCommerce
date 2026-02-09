package com.instacommerce.payment.dto.response;

public record ErrorDetail(
    String field,
    String message
) {
}
