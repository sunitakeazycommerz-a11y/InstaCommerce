package com.instacommerce.fulfillment.client;

import java.util.UUID;

public interface PaymentClient {
    void refund(UUID paymentId, long amountCents, String reason, String idempotencyKey);
}
