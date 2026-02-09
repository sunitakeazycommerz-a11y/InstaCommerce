package com.instacommerce.order.domain.statemachine;

import com.instacommerce.order.domain.model.OrderStatus;
import com.instacommerce.order.exception.InvalidOrderStateException;
import java.util.Map;
import java.util.Set;

public final class OrderStateMachine {
    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = Map.of(
        OrderStatus.PENDING, Set.of(OrderStatus.PLACED, OrderStatus.FAILED, OrderStatus.CANCELLED),
        OrderStatus.PLACED, Set.of(OrderStatus.PACKING, OrderStatus.CANCELLED),
        OrderStatus.PACKING, Set.of(OrderStatus.PACKED, OrderStatus.CANCELLED),
        OrderStatus.PACKED, Set.of(OrderStatus.OUT_FOR_DELIVERY, OrderStatus.CANCELLED),
        OrderStatus.OUT_FOR_DELIVERY, Set.of(OrderStatus.DELIVERED),
        OrderStatus.DELIVERED, Set.of(),
        OrderStatus.CANCELLED, Set.of(),
        OrderStatus.FAILED, Set.of()
    );

    private OrderStateMachine() {
    }

    public static void validate(OrderStatus from, OrderStatus to) {
        if (!TRANSITIONS.getOrDefault(from, Set.of()).contains(to)) {
            throw new InvalidOrderStateException("Cannot transition from " + from + " to " + to);
        }
    }
}
