package com.instacommerce.order.client;

public record PaymentAuthorizeRequest(
    String orderId,
    long amountCents,
    String currency,
    String idempotencyKey
) {
}
