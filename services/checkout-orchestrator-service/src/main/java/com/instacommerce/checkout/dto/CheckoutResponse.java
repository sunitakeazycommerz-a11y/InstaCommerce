package com.instacommerce.checkout.dto;

public record CheckoutResponse(
    String orderId,
    String status,
    long totalCents,
    int estimatedDeliveryMinutes,
    String workflowId
) {
    public CheckoutResponse(String orderId, String status, long totalCents, int estimatedDeliveryMinutes) {
        this(orderId, status, totalCents, estimatedDeliveryMinutes, null);
    }

    public static CheckoutResponse success(String orderId, long totalCents, int estimatedDeliveryMinutes) {
        return new CheckoutResponse(orderId, "COMPLETED", totalCents, estimatedDeliveryMinutes, null);
    }

    public static CheckoutResponse success(String orderId, long totalCents, int estimatedDeliveryMinutes, String workflowId) {
        return new CheckoutResponse(orderId, "COMPLETED", totalCents, estimatedDeliveryMinutes, workflowId);
    }

    public static CheckoutResponse failed(String reason) {
        return new CheckoutResponse(null, "FAILED: " + reason, 0, 0, null);
    }

    public static CheckoutResponse failed(String reason, String workflowId) {
        return new CheckoutResponse(null, "FAILED: " + reason, 0, 0, workflowId);
    }

    public CheckoutResponse withWorkflowId(String resolvedWorkflowId) {
        return new CheckoutResponse(orderId, status, totalCents, estimatedDeliveryMinutes, resolvedWorkflowId);
    }
}
