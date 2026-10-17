package com.instacommerce.order.dto.response;

public record OrderCreationResult(
    String orderId,
    int estimatedDeliveryMinutes
) {
    public OrderCreationResult(String orderId) {
        this(orderId, 15);
    }
}
