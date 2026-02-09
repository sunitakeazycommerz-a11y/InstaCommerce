package com.instacommerce.fulfillment.client;

public record RefundRequest(
    long amountCents,
    String reason,
    String idempotencyKey
) {
}
