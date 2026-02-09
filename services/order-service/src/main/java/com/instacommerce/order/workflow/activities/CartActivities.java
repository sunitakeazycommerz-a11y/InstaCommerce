package com.instacommerce.order.workflow.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface CartActivities {
    @ActivityMethod
    void clearCart(String userId);
}
