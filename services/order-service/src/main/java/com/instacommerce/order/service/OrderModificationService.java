package com.instacommerce.order.service;

import com.instacommerce.order.consumer.FulfillmentOrderEvent;
import com.instacommerce.order.domain.model.Order;
import com.instacommerce.order.domain.model.OrderItem;
import com.instacommerce.order.domain.model.OrderRefundCalculator;
import com.instacommerce.order.domain.model.OrderStatus;
import com.instacommerce.order.exception.IllegalOrderStateTransitionException;
import com.instacommerce.order.exception.OrderAmendmentForbiddenException;
import com.instacommerce.order.repository.OrderRepository;
import com.instacommerce.order.service.OrderService.PartialCancellationResult;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderModificationService {
    private static final Logger logger = LoggerFactory.getLogger(OrderModificationService.class);

    private final OrderRepository orderRepository;
    private final OutboxService outboxService;
    private final AuditLogService auditLogService;
    private final OrderLifecycleService lifecycleService;

    public OrderModificationService(OrderRepository orderRepository,
                                    OutboxService outboxService,
                                    AuditLogService auditLogService,
                                    OrderLifecycleService lifecycleService) {
        this.orderRepository = orderRepository;
        this.outboxService = outboxService;
        this.auditLogService = auditLogService;
        this.lifecycleService = lifecycleService;
    }

    @Transactional
    public void validateOrderAmendmentWindow(UUID orderId, UUID requesterId, boolean bypassOwnershipCheck) {
        Order order = lifecycleService.lockOrder(orderId);
        if (!bypassOwnershipCheck && (requesterId == null || !order.getUserId().equals(requesterId))) {
            throw new AccessDeniedException("Cannot amend another user's order");
        }
        if (order.getStatus() != OrderStatus.PENDING && order.getStatus() != OrderStatus.PLACED) {
            throw new OrderAmendmentForbiddenException(
                "Order amendment rejected: modifications only permitted prior to packing. Current status: "
                    + order.getStatus());
        }
    }

    @Retryable(
        retryFor = {ObjectOptimisticLockingFailureException.class, OptimisticLockingFailureException.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 50, maxDelay = 500, multiplier = 2.0))
    @Transactional
    public void applyOrderModification(FulfillmentOrderEvent event) {
        UUID orderId = Objects.requireNonNull(event.orderId(), "orderId must not be null");
        Order order = lifecycleService.lockOrder(orderId);
        if (order.getStatus().isTerminal()) {
            logger.warn("Ignoring OrderModified event for order {} in terminal state {}", orderId, order.getStatus());
            return;
        }
        if (event.newVersion() != null && order.getVersion() >= event.newVersion()) {
            logger.info("Ignoring idempotent/stale OrderModified event for order {} currentVersion={} eventVersion={}",
                orderId, order.getVersion(), event.newVersion());
            return;
        }

        List<FulfillmentOrderEvent.OrderItemDelta> deltas = collectDeltas(event);
        long computedGrossRemoved = applyItemDeltas(order, deltas);
        long previousTotal = order.getTotalCents();

        long priceDeltaCents;
        long refundCents;
        boolean hasExplicitPriceAdjustment = event.priceDeltaCents() != null
            || (event.refundCents() != null && event.refundCents() > 0);
        if (hasExplicitPriceAdjustment) {
            long rawDelta = event.priceDeltaCents() != null
                ? event.priceDeltaCents()
                : -event.refundCents();
            if (rawDelta < 0) {
                long boundedRefund = Math.min(order.getTotalCents(), -rawDelta);
                priceDeltaCents = -boundedRefund;
                refundCents = boundedRefund;
            } else {
                priceDeltaCents = rawDelta;
                refundCents = 0L;
            }
            order.setTotalCents(Math.max(0L, order.getTotalCents() + priceDeltaCents));
            if (order.getSubtotalCents() > 0 && computedGrossRemoved > 0) {
                OrderRefundCalculator.PartialRefundBreakdown subtotalUpdate =
                    OrderRefundCalculator.calculateForRemovedGross(
                        order.getSubtotalCents(), order.getDiscountCents(), previousTotal, computedGrossRemoved);
                order.setSubtotalCents(subtotalUpdate.updatedSubtotalCents());
                order.setDiscountCents(subtotalUpdate.updatedDiscountCents());
            }
        } else if (computedGrossRemoved > 0) {
            OrderRefundCalculator.PartialRefundBreakdown breakdown =
                OrderRefundCalculator.calculateForRemovedGross(
                    order.getSubtotalCents(),
                    order.getDiscountCents(),
                    order.getTotalCents(),
                    computedGrossRemoved);
            refundCents = breakdown.refundCents();
            priceDeltaCents = -refundCents;
            if (order.getSubtotalCents() > 0) {
                order.setSubtotalCents(breakdown.updatedSubtotalCents());
                order.setDiscountCents(breakdown.updatedDiscountCents());
            }
            order.setTotalCents(breakdown.updatedTotalCents());
        } else {
            priceDeltaCents = 0L;
            refundCents = 0L;
        }

        long walletBefore = Math.max(0L, order.getWalletAmountCents());
        long walletRefundCents = computeWalletPartialRefund(order, previousTotal, refundCents);
        if (walletRefundCents > 0) {
            order.setWalletAmountCents(walletBefore - walletRefundCents);
        }

        long newTotal = order.getTotalCents();
        orderRepository.save(order);

        Map<String, Object> auditDetails = new LinkedHashMap<>();
        if (event.productId() != null) {
            auditDetails.put("productId", event.productId().toString());
        }
        if (event.missingQty() != null) {
            auditDetails.put("missingQty", event.missingQty());
        }
        auditDetails.put("refundCents", refundCents);
        auditDetails.put("priceDeltaCents", priceDeltaCents);
        auditLogService.log(order.getUserId(), "ORDER_MODIFIED", "Order", orderId.toString(), auditDetails);

        Map<String, Object> modifiedPayload = new LinkedHashMap<>();
        modifiedPayload.put("orderId", orderId.toString());
        if (event.productId() != null) {
            modifiedPayload.put("productId", event.productId().toString());
        }
        if (event.missingQty() != null) {
            modifiedPayload.put("missingQty", event.missingQty());
        }
        modifiedPayload.put("refundCents", refundCents);
        modifiedPayload.put("priceDeltaCents", priceDeltaCents);
        modifiedPayload.put("newTotalCents", newTotal);
        outboxService.publish("Order", orderId.toString(), "OrderModified", modifiedPayload);

        if (priceDeltaCents < 0 && refundCents > 0) {
            publishPartialRefundEvent(
                order, refundCents, walletBefore, walletRefundCents, priceDeltaCents, "ORDER_MODIFIED_PARTIAL_REFUND");
        }
    }

    @Retryable(
        retryFor = {ObjectOptimisticLockingFailureException.class, OptimisticLockingFailureException.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 50, maxDelay = 500, multiplier = 2.0))
    @Transactional
    public PartialCancellationResult cancelOrderItems(UUID orderId,
                                                      Map<UUID, Integer> itemQuantities,
                                                      String reason,
                                                      String changedBy,
                                                      UUID actorId) {
        Objects.requireNonNull(orderId, "orderId must not be null");
        if (itemQuantities == null || itemQuantities.isEmpty()) {
            throw new IllegalArgumentException("At least one item quantity must be specified for cancellation");
        }
        Order order = lifecycleService.lockOrder(orderId);
        if (order.getStatus().isTerminal()) {
            throw new IllegalOrderStateTransitionException(order.getStatus(), OrderStatus.CANCELLED);
        }

        long grossRemovedCents = 0L;
        for (Map.Entry<UUID, Integer> entry : itemQuantities.entrySet()) {
            UUID productId = Objects.requireNonNull(entry.getKey(), "productId must not be null");
            Integer cancelQty = entry.getValue();
            if (cancelQty == null || cancelQty <= 0) {
                throw new IllegalArgumentException("Cancellation quantity must be positive for product " + productId);
            }
            OrderItem item = order.getItems().stream()
                .filter(candidate -> productId.equals(candidate.getProductId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Product not found in order: " + productId));
            if (cancelQty > item.getQuantity()) {
                throw new IllegalArgumentException(
                    "Cannot cancel qty " + cancelQty + " for product " + productId
                        + " when order only has " + item.getQuantity());
            }
            grossRemovedCents += computeItemGrossReduction(item, cancelQty);
            int remainingQty = item.getQuantity() - cancelQty;
            if (remainingQty == 0) {
                order.getItems().remove(item);
            } else {
                item.setQuantity(remainingQty);
                item.setLineTotalCents((long) remainingQty * item.getUnitPriceCents());
            }
        }

        if (order.getItems().isEmpty()) {
            long fullRefundCents = order.getTotalCents();
            String effectiveReason = reason != null && !reason.isBlank() ? reason : "All order items cancelled";
            lifecycleService.cancelOrder(orderId, effectiveReason, changedBy != null ? changedBy : "system", actorId);
            return new PartialCancellationResult(orderId, fullRefundCents, 0L, true);
        }

        long previousTotal = order.getTotalCents();
        OrderRefundCalculator.PartialRefundBreakdown breakdown =
            OrderRefundCalculator.calculateForRemovedGross(
                order.getSubtotalCents(),
                order.getDiscountCents(),
                previousTotal,
                grossRemovedCents);
        if (order.getSubtotalCents() > 0) {
            order.setSubtotalCents(breakdown.updatedSubtotalCents());
            order.setDiscountCents(breakdown.updatedDiscountCents());
        }
        order.setTotalCents(breakdown.updatedTotalCents());

        long walletBefore = Math.max(0L, order.getWalletAmountCents());
        long walletRefundCents = computeWalletPartialRefund(order, previousTotal, breakdown.refundCents());
        if (walletRefundCents > 0) {
            order.setWalletAmountCents(walletBefore - walletRefundCents);
        }
        orderRepository.save(order);

        String effectiveReason = reason != null && !reason.isBlank() ? reason : "PARTIAL_ITEM_CANCELLATION";
        auditLogService.log(actorId != null ? actorId : order.getUserId(),
            "ORDER_PARTIALLY_CANCELLED",
            "Order",
            orderId.toString(),
            Map.of(
                "refundCents", breakdown.refundCents(),
                "remainingTotalCents", breakdown.updatedTotalCents(),
                "reason", effectiveReason));
        if (breakdown.refundCents() > 0) {
            publishPartialRefundEvent(
                order, breakdown.refundCents(), walletBefore, walletRefundCents,
                -breakdown.refundCents(), effectiveReason);
        }
        return new PartialCancellationResult(orderId, breakdown.refundCents(), breakdown.updatedTotalCents(), false);
    }

    private List<FulfillmentOrderEvent.OrderItemDelta> collectDeltas(FulfillmentOrderEvent event) {
        List<FulfillmentOrderEvent.OrderItemDelta> deltas = new ArrayList<>();
        if (event.itemDeltas() != null && !event.itemDeltas().isEmpty()) {
            deltas.addAll(event.itemDeltas());
        } else if (event.productId() != null) {
            int deltaQty = -(event.missingQty() != null && event.missingQty() > 0 ? event.missingQty() : 1);
            long deltaPrice = event.priceDeltaCents() != null
                ? event.priceDeltaCents()
                : (event.refundCents() != null ? -event.refundCents() : 0L);
            deltas.add(new FulfillmentOrderEvent.OrderItemDelta(event.productId(), deltaQty, deltaPrice));
        }
        return deltas;
    }

    private long applyItemDeltas(Order order, List<FulfillmentOrderEvent.OrderItemDelta> deltas) {
        long computedGrossRemoved = 0L;
        for (FulfillmentOrderEvent.OrderItemDelta delta : deltas) {
            if (delta.productId() == null) {
                continue;
            }
            int qtyDelta = delta.quantityDelta() != null ? delta.quantityDelta() : 0;
            Iterator<OrderItem> it = order.getItems().iterator();
            while (it.hasNext()) {
                OrderItem item = it.next();
                if (delta.productId().equals(item.getProductId())) {
                    if (qtyDelta < 0) {
                        int removedQty = Math.min(item.getQuantity(), -qtyDelta);
                        computedGrossRemoved += computeItemGrossReduction(item, removedQty);
                    }
                    int updatedQty = item.getQuantity() + qtyDelta;
                    if (updatedQty <= 0) {
                        item.setPickedStatus("SHORT_PICKED");
                        it.remove();
                    } else {
                        item.setQuantity(updatedQty);
                        item.setLineTotalCents(Math.max(0L, (long) updatedQty * item.getUnitPriceCents()));
                        if (qtyDelta < 0) {
                            item.setPickedStatus("PARTIAL");
                        }
                    }
                    break;
                }
            }
        }
        return computedGrossRemoved;
    }

    private long computeWalletPartialRefund(Order order, long previousTotalCents, long refundCents) {
        long walletRemaining = Math.max(0L, order.getWalletAmountCents());
        if (walletRemaining == 0L || refundCents <= 0L) {
            return 0L;
        }
        long pspCapacity = order.getPaymentId() != null ? Math.max(0L, previousTotalCents - walletRemaining) : 0L;
        return Math.min(walletRemaining, Math.max(0L, refundCents - pspCapacity));
    }

    private long computeItemGrossReduction(OrderItem item, int removedQty) {
        if (removedQty <= 0) {
            return 0L;
        }
        if (item.getUnitPriceCents() > 0) {
            return (long) removedQty * item.getUnitPriceCents();
        }
        if (item.getQuantity() > 0 && item.getLineTotalCents() > 0) {
            return (item.getLineTotalCents() * removedQty) / item.getQuantity();
        }
        return 0L;
    }

    private void publishPartialRefundEvent(Order order,
                                           long refundCents,
                                           long walletAmountCents,
                                           long walletRefundCents,
                                           long priceDeltaCents,
                                           String reason) {
        Map<String, Object> refundPayload = new LinkedHashMap<>();
        refundPayload.put("orderId", order.getId().toString());
        if (order.getPaymentId() != null) {
            refundPayload.put("paymentId", order.getPaymentId().toString());
        }
        refundPayload.put("refundCents", refundCents);
        refundPayload.put("walletAmountCents", walletAmountCents);
        refundPayload.put("walletRefundCents", walletRefundCents);
        refundPayload.put("refundedCents", refundCents);
        refundPayload.put("priceDeltaCents", priceDeltaCents);
        refundPayload.put("reason", reason);
        outboxService.publish("Order", order.getId().toString(), "OrderPartiallyRefunded", refundPayload);
    }
}
