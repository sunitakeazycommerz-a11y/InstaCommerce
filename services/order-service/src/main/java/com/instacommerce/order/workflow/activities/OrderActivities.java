package com.instacommerce.order.workflow.activities;

import com.instacommerce.order.workflow.model.CreateOrderCommand;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface OrderActivities {
    @ActivityMethod
    String createOrder(CreateOrderCommand command);

    @ActivityMethod
    void cancelOrder(String orderId, String reason);

    @ActivityMethod
    void updateOrderStatus(String orderId, String status);
}
