package com.instacommerce.wallet.dto.response;

public record ErrorDetail(
    String field,
    String message
) {
}
