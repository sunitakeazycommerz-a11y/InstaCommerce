package com.instacommerce.fulfillment.event;

import com.instacommerce.fulfillment.client.OrderClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class OrderStatusEventListener {
    private static final Logger logger = LoggerFactory.getLogger(OrderStatusEventListener.class);

    private final OrderClient orderClient;

    public OrderStatusEventListener(OrderClient orderClient) {
        this.orderClient = orderClient;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOrderStatusUpdate(OrderStatusUpdateEvent event) {
        logger.info("Updating order {} status to {} after TX commit", event.orderId(), event.status());
        orderClient.updateStatus(event.orderId(), event.status(), event.note());
    }
}
