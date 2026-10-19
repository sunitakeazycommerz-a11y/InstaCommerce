package com.instacommerce.order.domain.statemachine;

import com.instacommerce.order.domain.model.OrderStatus;
import com.instacommerce.order.exception.IllegalOrderStateTransitionException;
import java.util.Map;
import java.util.Set;

public final class OrderStateMachine {
    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = Map.of(
        OrderStatus.PENDING, Set.of(OrderStatus.PLACED, OrderStatus.FAILED, OrderStatus.CANCELLED),
        OrderStatus.PLACED, Set.of(OrderStatus.PACKING, OrderStatus.CANCELLED, OrderStatus.FAILED),
        OrderStatus.PACKING, Set.of(OrderStatus.PACKED, OrderStatus.CANCELLED, OrderStatus.FAILED),
        OrderStatus.PACKED, Set.of(OrderStatus.OUT_FOR_DELIVERY, OrderStatus.CANCELLED, OrderStatus.FAILED),
        OrderStatus.OUT_FOR_DELIVERY, Set.of(OrderStatus.DELIVERED, OrderStatus.CANCELLED, OrderStatus.FAILED),
        OrderStatus.DELIVERED, Set.of(),
        OrderStatus.CANCELLED, Set.of(),
        OrderStatus.FAILED, Set.of()
    );

    private OrderStateMachine() {
    }

    public static boolean canTransition(OrderStatus from, OrderStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public static void validate(OrderStatus from, OrderStatus to) {
        if (from == null || to == null || !canTransition(from, to)) {
            throw new IllegalOrderStateTransitionException(from, to);
        }
    }
}
