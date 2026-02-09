package com.instacommerce.order.dto.response;

public record CheckoutResponse(
    String orderId,
    String workflowId,
    String errorMessage
) {
    public CheckoutResponse(String orderId, String workflowId) {
        this(orderId, workflowId, null);
    }
}
