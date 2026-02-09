package com.instacommerce.payment.gateway;

public record GatewayVoidResult(
    boolean success,
    String failureReason
) {
    public static GatewayVoidResult success() {
        return new GatewayVoidResult(true, null);
    }

    public static GatewayVoidResult failure(String reason) {
        return new GatewayVoidResult(false, reason);
    }
}
