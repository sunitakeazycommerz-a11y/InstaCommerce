package com.instacommerce.fraud.dto.response;

public record ErrorDetail(
        String field,
        String message
) {
}
