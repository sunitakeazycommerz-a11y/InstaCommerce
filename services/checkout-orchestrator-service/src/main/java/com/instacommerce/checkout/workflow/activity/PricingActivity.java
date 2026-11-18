package com.instacommerce.checkout.workflow.activity;

import com.instacommerce.checkout.dto.PricingRequest;
import com.instacommerce.checkout.dto.PricingResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface PricingActivity {

    @ActivityMethod
    PricingResult calculatePrice(PricingRequest request);

    @ActivityMethod(name = "PricingUnredeemCoupon")
    void unredeemCoupon(String code, String userId, String orderId);
}
