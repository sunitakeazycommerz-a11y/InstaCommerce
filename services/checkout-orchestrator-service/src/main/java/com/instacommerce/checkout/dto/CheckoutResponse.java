package com.instacommerce.checkout.dto;

public record CheckoutResponse(
    String orderId,
    String status,
    long totalCents,
    int estimatedDeliveryMinutes
) {
    public static CheckoutResponse success(String orderId, long totalCents, int estimatedDeliveryMinutes) {
        return new CheckoutResponse(orderId, "COMPLETED", totalCents, estimatedDeliveryMinutes);
    }

    public static CheckoutResponse failed(String reason) {
        return new CheckoutResponse(null, "FAILED: " + reason, 0, 0);
    }
}
