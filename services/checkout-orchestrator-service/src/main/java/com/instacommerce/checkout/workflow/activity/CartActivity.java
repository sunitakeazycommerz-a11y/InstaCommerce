package com.instacommerce.checkout.workflow.activity;

import com.instacommerce.checkout.dto.CartValidationResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface CartActivity {

    @ActivityMethod
    CartValidationResult validateCart(String userId);

    @ActivityMethod
    void clearCart(String userId);
}
