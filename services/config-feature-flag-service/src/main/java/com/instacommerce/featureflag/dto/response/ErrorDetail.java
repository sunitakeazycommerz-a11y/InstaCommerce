package com.instacommerce.featureflag.dto.response;

public record ErrorDetail(
    String field,
    String message
) {
}
