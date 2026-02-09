package com.instacommerce.checkout.workflow.activity;

import com.instacommerce.checkout.dto.OrderCreateRequest;
import com.instacommerce.checkout.dto.OrderCreationResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface OrderActivity {

    @ActivityMethod
    OrderCreationResult createOrder(OrderCreateRequest request);

    @ActivityMethod
    void cancelOrder(String orderId);
}
