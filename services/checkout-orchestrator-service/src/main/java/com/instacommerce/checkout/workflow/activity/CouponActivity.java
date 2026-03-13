package com.instacommerce.checkout.workflow.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface CouponActivity {
    @ActivityMethod
    void redeemCoupon(String code, String userId, String orderId, long discountCents);

    @ActivityMethod
    void unredeemCoupon(String code, String userId, String orderId);
}
