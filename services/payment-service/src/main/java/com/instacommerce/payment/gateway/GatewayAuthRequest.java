package com.instacommerce.payment.gateway;

public record GatewayAuthRequest(
    long amountCents,
    String currency,
    String idempotencyKey,
    String paymentMethod
) {
}
