package com.instacommerce.order.workflow.activities;

import com.instacommerce.order.client.PaymentClient;
import com.instacommerce.order.workflow.model.PaymentResult;
import org.springframework.stereotype.Component;

@Component
public class PaymentActivitiesImpl implements PaymentActivities {
    private final PaymentClient paymentClient;

    public PaymentActivitiesImpl(PaymentClient paymentClient) {
        this.paymentClient = paymentClient;
    }

    @Override
    public PaymentResult authorizePayment(String orderId, long amountCents, String currency, String idempotencyKey) {
        return paymentClient.authorizePayment(orderId, amountCents, currency, idempotencyKey);
    }

    @Override
    public void capturePayment(String paymentId) {
        paymentClient.capturePayment(paymentId);
    }

    @Override
    public void voidPayment(String paymentId) {
        paymentClient.voidPayment(paymentId);
    }
}
