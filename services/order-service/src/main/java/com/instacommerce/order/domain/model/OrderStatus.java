package com.instacommerce.order.domain.model;

import com.instacommerce.order.domain.statemachine.OrderStateMachine;

public enum OrderStatus {
    PENDING,
    PLACED,
    PACKING,
    PACKED,
    OUT_FOR_DELIVERY,
    DELIVERED,
    CANCELLED,
    FAILED;

    public boolean isTerminal() {
        return this == DELIVERED || this == CANCELLED || this == FAILED;
    }

    public boolean canTransitionTo(OrderStatus next) {
        return OrderStateMachine.canTransition(this, next);
    }
}
