package com.instacommerce.checkout.workflow.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface PromotionActivity {
    @ActivityMethod
    void redeemPromotion(String promotionId, String userId, String orderId, long discountCents);

    @ActivityMethod
    void rollbackPromotion(String promotionId, String userId, String orderId);
}
