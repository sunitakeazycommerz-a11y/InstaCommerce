package com.instacommerce.audit.dto;

public record ErrorDetail(
    String field,
    String message
) {
}
