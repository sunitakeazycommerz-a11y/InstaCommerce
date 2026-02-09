package com.instacommerce.checkout.dto;

public record ErrorDetail(
    String field,
    String message
) {}
