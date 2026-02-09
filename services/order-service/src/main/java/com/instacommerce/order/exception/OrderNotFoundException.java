package com.instacommerce.order.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class OrderNotFoundException extends ApiException {
    public OrderNotFoundException(UUID orderId) {
        super(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found: " + orderId);
    }
}
