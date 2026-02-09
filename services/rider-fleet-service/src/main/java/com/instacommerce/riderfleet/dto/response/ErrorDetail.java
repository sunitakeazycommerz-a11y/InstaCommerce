package com.instacommerce.riderfleet.dto.response;

public record ErrorDetail(
    String field,
    String message
) {
}
