package com.instacommerce.payment.gateway;

public record GatewayAuthResult(
    boolean success,
    String pspReference,
    String declineReason
) {
    public static GatewayAuthResult success(String pspReference) {
        return new GatewayAuthResult(true, pspReference, null);
    }

    public static GatewayAuthResult declined(String reason) {
        return new GatewayAuthResult(false, null, reason);
    }
}
