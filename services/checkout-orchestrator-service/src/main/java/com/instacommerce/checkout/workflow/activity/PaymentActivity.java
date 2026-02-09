package com.instacommerce.checkout.workflow.activity;

import com.instacommerce.checkout.dto.PaymentAuthResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface PaymentActivity {

    @ActivityMethod
    PaymentAuthResult authorizePayment(long amountCents, String paymentMethodId, String idempotencyKey);

    @ActivityMethod
    void capturePayment(String paymentId);

    @ActivityMethod
    void voidPayment(String paymentId);
}
