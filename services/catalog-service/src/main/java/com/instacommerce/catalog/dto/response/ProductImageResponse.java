package com.instacommerce.catalog.dto.response;

public record ProductImageResponse(
    String url,
    String altText,
    boolean isPrimary
) {
}
