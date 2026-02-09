package com.instacommerce.checkout.dto;

public record CartItem(
    String productId,
    int quantity,
    long unitPriceCents
) {}
