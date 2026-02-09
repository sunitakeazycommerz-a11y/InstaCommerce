package com.instacommerce.warehouse.dto.response;

public record ErrorDetail(
    String field,
    String message
) {
}
