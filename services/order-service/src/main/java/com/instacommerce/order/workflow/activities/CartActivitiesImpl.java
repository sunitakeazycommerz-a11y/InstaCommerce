package com.instacommerce.order.workflow.activities;

import com.instacommerce.order.client.CartClient;
import org.springframework.stereotype.Component;

@Component
public class CartActivitiesImpl implements CartActivities {
    private final CartClient cartClient;

    public CartActivitiesImpl(CartClient cartClient) {
        this.cartClient = cartClient;
    }

    @Override
    public void clearCart(String userId) {
        cartClient.clearCart(userId);
    }
}
