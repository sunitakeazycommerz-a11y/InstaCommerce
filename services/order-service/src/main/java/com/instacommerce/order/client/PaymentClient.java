package com.instacommerce.order.client;

import com.instacommerce.order.workflow.model.PaymentResult;

public interface PaymentClient {
    PaymentResult authorizePayment(String orderId, long amountCents, String currency, String idempotencyKey);

    void capturePayment(String paymentId);

    void voidPayment(String paymentId);
}
