package com.instacommerce.order.service;

import com.instacommerce.order.consumer.FulfillmentOrderEvent;
import com.instacommerce.order.domain.model.OrderStatus;
import com.instacommerce.order.dto.response.OrderResponse;
import com.instacommerce.order.dto.response.OrderStatusResponse;
import com.instacommerce.order.dto.response.OrderSummaryResponse;
import com.instacommerce.order.workflow.model.CreateOrderCommand;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class OrderService {
    private final OrderCreationService creationService;
    private final OrderLifecycleService lifecycleService;
    private final OrderModificationService modificationService;

    public OrderService(OrderCreationService creationService,
                        OrderLifecycleService lifecycleService,
                        OrderModificationService modificationService) {
        this.creationService = Objects.requireNonNull(creationService, "creationService must not be null");
        this.lifecycleService = Objects.requireNonNull(lifecycleService, "lifecycleService must not be null");
        this.modificationService = Objects.requireNonNull(modificationService, "modificationService must not be null");
    }

    public String createOrder(CreateOrderCommand command) {
        return creationService.createOrder(command);
    }

    public OrderResponse getOrder(UUID orderId, UUID requesterId, boolean isAdmin) {
        return lifecycleService.getOrder(orderId, requesterId, isAdmin);
    }

    public OrderStatusResponse getOrderStatus(UUID orderId, UUID requesterId, boolean isAdmin) {
        return lifecycleService.getOrderStatus(orderId, requesterId, isAdmin);
    }

    public Page<OrderSummaryResponse> listOrders(UUID userId, Pageable pageable) {
        return lifecycleService.listOrders(userId, pageable);
    }

    public void cancelOrder(UUID orderId, String reason, String changedBy) {
        cancelOrder(orderId, reason, changedBy, null);
    }

    public void cancelOrderByUser(UUID orderId, UUID userId, String reason) {
        lifecycleService.cancelOrderByUser(orderId, userId, reason);
    }

    public void cancelOrder(UUID orderId, String reason, String changedBy, UUID actorId) {
        lifecycleService.cancelOrder(orderId, reason, changedBy, actorId);
    }

    public void updateOrderStatus(UUID orderId, OrderStatus newStatus, String changedBy, String note) {
        updateOrderStatus(orderId, newStatus, changedBy, note, null);
    }

    public void updateOrderStatus(UUID orderId, OrderStatus newStatus, String changedBy, String note, UUID actorId) {
        lifecycleService.updateOrderStatus(orderId, newStatus, changedBy, note, actorId);
    }

    public void advanceLifecycleFromFulfillment(UUID orderId, OrderStatus targetStatus, String changedBy, String note) {
        lifecycleService.advanceLifecycleFromFulfillment(orderId, targetStatus, changedBy, note);
    }

    public void recordFulfillmentAuditNote(UUID orderId, String changedBy, String note) {
        lifecycleService.recordFulfillmentAuditNote(orderId, changedBy, note);
    }

    public void validateOrderAmendmentWindow(UUID orderId) {
        validateOrderAmendmentWindow(orderId, null, true);
    }

    public void validateOrderAmendmentWindow(UUID orderId, UUID requesterId, boolean bypassOwnershipCheck) {
        modificationService.validateOrderAmendmentWindow(orderId, requesterId, bypassOwnershipCheck);
    }

    public void applyOrderModification(UUID orderId, UUID productId, int missingQty, long refundCents) {
        applyOrderModification(new FulfillmentOrderEvent(orderId, productId, missingQty, refundCents, null, null, null));
    }

    public void applyOrderModification(FulfillmentOrderEvent event) {
        modificationService.applyOrderModification(event);
    }

    public PartialCancellationResult cancelOrderItems(UUID orderId,
                                                      Map<UUID, Integer> itemQuantities,
                                                      String reason,
                                                      String changedBy,
                                                      UUID actorId) {
        return modificationService.cancelOrderItems(orderId, itemQuantities, reason, changedBy, actorId);
    }

    public int checkAndRecordSlaBreaches() {
        return lifecycleService.checkAndRecordSlaBreaches();
    }

    public record PartialCancellationResult(
        UUID orderId,
        long refundCents,
        long remainingTotalCents,
        boolean fullOrderCancelled
    ) {
    }
}
