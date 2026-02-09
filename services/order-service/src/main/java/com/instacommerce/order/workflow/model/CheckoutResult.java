package com.instacommerce.order.workflow.model;

public record CheckoutResult(
    boolean success,
    String orderId,
    String errorMessage
) {
    public static CheckoutResult success(String orderId) {
        return new CheckoutResult(true, orderId, null);
    }

    public static CheckoutResult failure(String errorMessage) {
        return new CheckoutResult(false, null, errorMessage);
    }

    public boolean isSuccess() {
        return success;
    }
}
