package com.instacommerce.fulfillment.service;

import com.instacommerce.fulfillment.client.InventoryClient;
import com.instacommerce.fulfillment.client.PaymentClient;
import com.instacommerce.fulfillment.domain.model.PickItem;
import com.instacommerce.fulfillment.domain.model.PickItemStatus;
import com.instacommerce.fulfillment.domain.model.PickTask;
import com.instacommerce.fulfillment.event.MissingItemEvent;
import java.util.LinkedHashMap;
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
        boolean isSubstituted = item.getStatus() == PickItemStatus.SUBSTITUTED && item.getSubstituteProductId() != null;
        int substitutedQty = isSubstituted ? item.getPickedQty() : 0;
        if (missingQty <= 0 && substitutedQty <= 0) {
            return;
        }
        if (item.getQuantity() <= 0) {
            throw new IllegalStateException("Invalid item quantity for product " + item.getProductId());
        }
        if (missingQty > 0 && task.getPaymentId() == null) {
            throw new IllegalStateException("Payment reference missing for order " + task.getOrderId());
        }
        long refundAmount = missingQty > 0
            ? Math.round((double) item.getLineTotalCents() * missingQty / item.getQuantity())
            : 0L;
        String referenceId = task.getOrderId() + ":" + item.getProductId();

        if (isSubstituted && substitutedQty > 0) {
            substituteItem(task, item, substitutedQty, referenceId);
        }

        if (missingQty > 0) {
            outboxService.publish("Fulfillment", task.getOrderId().toString(), "OrderModified",
                Map.of(
                    "orderId", task.getOrderId(),
                    "productId", item.getProductId(),
                    "missingQty", missingQty,
                    "refundCents", refundAmount
                ));

            eventPublisher.publishEvent(new MissingItemEvent(
                item.getProductId(), task.getStoreId(), missingQty, referenceId,
                task.getPaymentId(), refundAmount));
        }
    }

    @Transactional
    public void substituteItem(PickTask task, PickItem item, int approvedSubstitutedQty, String referenceId) {
        if (approvedSubstitutedQty <= 0 || item.getSubstituteProductId() == null) {
            return;
        }
        UUID substituteProductId = item.getSubstituteProductId();
        inventoryClient.releaseStock(
            item.getProductId(),
            task.getStoreId(),
            approvedSubstitutedQty,
            "FULFILLMENT_SUBSTITUTED_ORIGINAL_RELEASE",
            referenceId + ":release"
        );
        inventoryClient.releaseStock(
            substituteProductId,
            task.getStoreId(),
            -approvedSubstitutedQty,
            "FULFILLMENT_SUBSTITUTION_REPLACEMENT",
            referenceId + ":reserve"
        );
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", task.getOrderId());
        payload.put("storeId", task.getStoreId());
        payload.put("originalProductId", item.getProductId());
        payload.put("substituteProductId", substituteProductId);
        payload.put("substitutedQuantity", approvedSubstitutedQty);
        outboxService.publish("Fulfillment", task.getOrderId().toString(), "ItemSubstituted", payload);
    }

    /**
     * Phase 2 — called after TX commit by MissingItemEventListener.
     * Makes HTTP calls to inventory and payment services outside any transaction.
     */
    public void releaseStockAndRefund(MissingItemEvent event) {
        if (event.missingQty() > 0 && event.substituteProductId() == null) {
            try {
                inventoryClient.releaseStock(event.productId(), event.storeId(), -event.missingQty(),
                    "FULFILLMENT_MISSING", event.referenceId());
            } catch (Exception ex) {
                logger.error("Failed to adjust stock for reference {}: {}", event.referenceId(), ex.getMessage());
            }
        }
        if (event.refundAmountCents() > 0 && event.paymentId() != null) {
            try {
                paymentClient.refund(event.paymentId(), event.refundAmountCents(),
                    "Item missing", "missing-" + event.referenceId());
            } catch (Exception ex) {
                logger.error("Failed to refund for reference {}: {}", event.referenceId(), ex.getMessage());
            }
        }
    }
}
