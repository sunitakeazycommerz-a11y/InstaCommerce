package com.instacommerce.checkout.dto;

public record OrderCreationResult(
    String orderId,
    int estimatedDeliveryMinutes
) {}
