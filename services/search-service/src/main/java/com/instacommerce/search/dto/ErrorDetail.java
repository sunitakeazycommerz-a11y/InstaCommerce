package com.instacommerce.search.dto;

public record ErrorDetail(
    String field,
    String message
) {
}
