package com.instacommerce.payment.gateway;

public interface PaymentGateway {
    GatewayAuthResult authorize(GatewayAuthRequest request);

    GatewayCaptureResult capture(String pspReference, long amountCents, String idempotencyKey);

    GatewayVoidResult voidAuth(String pspReference, String idempotencyKey);

    GatewayRefundResult refund(String pspReference, long amountCents, String idempotencyKey);
}
