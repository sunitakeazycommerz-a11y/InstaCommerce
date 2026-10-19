package com.instacommerce.order.exception;

import com.instacommerce.order.domain.model.OrderStatus;
import org.springframework.http.HttpStatus;

public class IllegalOrderStateTransitionException extends InvalidOrderStateException {
    public IllegalOrderStateTransitionException(OrderStatus from, OrderStatus to) {
        super(HttpStatus.CONFLICT, "ILLEGAL_ORDER_STATE_TRANSITION",
            "Cannot transition order from " + from + " to " + to + (from != null && from.isTerminal() ? " (terminal state)" : ""));
    }

    public IllegalOrderStateTransitionException(String message) {
        super(HttpStatus.CONFLICT, "ILLEGAL_ORDER_STATE_TRANSITION", message);
    }
}
