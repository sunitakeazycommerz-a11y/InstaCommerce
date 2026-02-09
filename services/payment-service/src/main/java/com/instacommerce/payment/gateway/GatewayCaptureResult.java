package com.instacommerce.payment.gateway;

public record GatewayCaptureResult(
    boolean success,
    String failureReason
) {
    public static GatewayCaptureResult success() {
        return new GatewayCaptureResult(true, null);
    }

    public static GatewayCaptureResult failure(String reason) {
        return new GatewayCaptureResult(false, reason);
    }
}
