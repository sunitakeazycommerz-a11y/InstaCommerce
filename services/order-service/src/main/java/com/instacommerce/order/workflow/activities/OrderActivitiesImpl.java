package com.instacommerce.order.workflow.activities;

import com.instacommerce.order.domain.model.OrderStatus;
import com.instacommerce.order.service.OrderService;
import com.instacommerce.order.workflow.model.CreateOrderCommand;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class OrderActivitiesImpl implements OrderActivities {
    private final OrderService orderService;

    public OrderActivitiesImpl(OrderService orderService) {
        this.orderService = orderService;
    }

    @Override
    public String createOrder(CreateOrderCommand command) {
        return orderService.createOrder(command);
    }

    @Override
    public void cancelOrder(String orderId, String reason) {
        orderService.cancelOrder(UUID.fromString(orderId), reason, "system");
    }

    @Override
    public void updateOrderStatus(String orderId, String status) {
        orderService.updateOrderStatus(UUID.fromString(orderId), OrderStatus.valueOf(status), "system", "workflow");
    }
}
