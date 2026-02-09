package com.instacommerce.payment.gateway;

public record GatewayRefundResult(
    boolean success,
    String refundId,
    String failureReason
) {
    public static GatewayRefundResult success(String refundId) {
        return new GatewayRefundResult(true, refundId, null);
    }

    public static GatewayRefundResult failure(String reason) {
        return new GatewayRefundResult(false, null, reason);
    }
}
