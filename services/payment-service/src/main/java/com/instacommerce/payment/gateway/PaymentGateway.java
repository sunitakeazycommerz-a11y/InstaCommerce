package com.instacommerce.payment.gateway;

public interface PaymentGateway {
    GatewayAuthResult authorize(GatewayAuthRequest request);

    GatewayCaptureResult capture(String pspReference, long amountCents);

    GatewayVoidResult voidAuth(String pspReference);

    GatewayRefundResult refund(String pspReference, long amountCents, String idempotencyKey);
}
