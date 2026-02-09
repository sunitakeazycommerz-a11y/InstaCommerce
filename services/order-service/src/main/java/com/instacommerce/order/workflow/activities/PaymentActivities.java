package com.instacommerce.order.workflow.activities;

import com.instacommerce.order.workflow.model.PaymentResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface PaymentActivities {
    @ActivityMethod
    PaymentResult authorizePayment(String orderId, long amountCents, String currency, String idempotencyKey);

    @ActivityMethod
    void capturePayment(String paymentId);

    @ActivityMethod
    void voidPayment(String paymentId);
}
