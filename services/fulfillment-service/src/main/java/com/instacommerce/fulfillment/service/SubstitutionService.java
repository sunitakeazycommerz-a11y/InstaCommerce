package com.instacommerce.fulfillment.service;

import com.instacommerce.fulfillment.client.InventoryClient;
import com.instacommerce.fulfillment.client.PaymentClient;
import com.instacommerce.fulfillment.domain.model.PickItem;
import com.instacommerce.fulfillment.domain.model.PickTask;
import com.instacommerce.fulfillment.event.MissingItemEvent;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubstitutionService {
    private static final Logger logger = LoggerFactory.getLogger(SubstitutionService.class);

    private final InventoryClient inventoryClient;
    private final PaymentClient paymentClient;
    private final OutboxService outboxService;
    private final ApplicationEventPublisher eventPublisher;

    public SubstitutionService(InventoryClient inventoryClient,
                               PaymentClient paymentClient,
                               OutboxService outboxService,
                               ApplicationEventPublisher eventPublisher) {
        this.inventoryClient = inventoryClient;
        this.paymentClient = paymentClient;
        this.outboxService = outboxService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Phase 1 — must be called inside an existing transaction.
     * Validates, publishes the outbox event, and schedules HTTP calls for after commit.
     */
    @Transactional
    public void handleMissingItem(PickTask task, PickItem item, int missingQty) {
        if (missingQty <= 0) {
            return;
        }
        if (item.getQuantity() <= 0) {
            throw new IllegalStateException("Invalid item quantity for product " + item.getProductId());
        }
        if (task.getPaymentId() == null) {
            throw new IllegalStateException("Payment reference missing for order " + task.getOrderId());
        }
        // Round to nearest cent to avoid truncation from integer division
        long refundAmount = Math.round((double) item.getLineTotalCents() * missingQty / item.getQuantity());
        String referenceId = task.getOrderId() + ":" + item.getProductId();

        outboxService.publish("Fulfillment", task.getOrderId().toString(), "OrderModified",
            Map.of(
                "orderId", task.getOrderId(),
                "productId", item.getProductId(),
                "missingQty", missingQty,
                "refundCents", refundAmount
            ));

        // Schedule HTTP calls to run after the transaction commits
        eventPublisher.publishEvent(new MissingItemEvent(
            item.getProductId(), task.getStoreId(), missingQty, referenceId,
            task.getPaymentId(), refundAmount));
    }

    /**
     * Phase 2 — called after TX commit by MissingItemEventListener.
     * Makes HTTP calls to inventory and payment services outside any transaction.
     */
    public void releaseStockAndRefund(MissingItemEvent event) {
        try {
            inventoryClient.releaseStock(event.productId(), event.storeId(), event.missingQty(),
                "FULFILLMENT_MISSING", event.referenceId());
        } catch (Exception ex) {
            logger.error("Failed to release stock for reference {}: {}", event.referenceId(), ex.getMessage());
        }
        try {
            paymentClient.refund(event.paymentId(), event.refundAmountCents(),
                "Item missing", "missing-" + event.referenceId());
        } catch (Exception ex) {
            logger.error("Failed to refund for reference {}: {}", event.referenceId(), ex.getMessage());
        }
    }
}
