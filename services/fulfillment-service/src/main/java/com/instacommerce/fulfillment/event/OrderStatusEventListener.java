package com.instacommerce.fulfillment.event;

import com.instacommerce.fulfillment.client.OrderClient;
import com.instacommerce.fulfillment.config.FulfillmentProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class OrderStatusEventListener {
    private static final Logger logger = LoggerFactory.getLogger(OrderStatusEventListener.class);

    private final OrderClient orderClient;
    private final FulfillmentProperties fulfillmentProperties;
    private final Counter callbackSkippedCounter;

    public OrderStatusEventListener(OrderClient orderClient,
                                    FulfillmentProperties fulfillmentProperties,
                                    MeterRegistry meterRegistry) {
        this.orderClient = orderClient;
        this.fulfillmentProperties = fulfillmentProperties;
        this.callbackSkippedCounter = Counter.builder("fulfillment.order_status_callback.skipped")
                .description("Order-status HTTP callbacks skipped because the legacy callback is disabled")
                .register(meterRegistry);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOrderStatusUpdate(OrderStatusUpdateEvent event) {
        if (!fulfillmentProperties.getChoreography().isOrderStatusCallbackEnabled()) {
            logger.info("Order-status HTTP callback disabled; skipping update for order {} status {}",
                    event.orderId(), event.status());
            callbackSkippedCounter.increment();
            return;
        }
        logger.info("Updating order {} status to {} after TX commit", event.orderId(), event.status());
        orderClient.updateStatus(event.orderId(), event.status(), event.note());
    }
}
