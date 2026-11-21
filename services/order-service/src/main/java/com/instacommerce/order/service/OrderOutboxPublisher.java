package com.instacommerce.order.service;

import com.instacommerce.contracts.orders.CancellationReason;
import com.instacommerce.order.domain.model.Order;
import com.instacommerce.order.domain.model.OrderItem;
import com.instacommerce.order.domain.model.OrderStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class OrderOutboxPublisher {
    private static final String SAGA_ROLLBACK_REASON = "CHECKOUT_SAGA_ROLLBACK";

    private final OutboxService outboxService;
    private final AuditLogService auditLogService;

    OrderOutboxPublisher(OutboxService outboxService, AuditLogService auditLogService) {
        this.outboxService = outboxService;
        this.auditLogService = auditLogService;
    }

    void publishCancelled(Order saved,
                          OrderStatus previous,
                          String reason,
                          String effectiveReason,
                          UUID actorId,
                          boolean emitStatusChanged,
                          boolean slaNewlyBreached) {
        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("orderUserId", saved.getUserId());
        auditDetails.put("reason", effectiveReason);
        auditLogService.log(actorId, "ORDER_CANCELLED", "Order", saved.getId().toString(), auditDetails);
        if (emitStatusChanged) {
            outboxService.publish("Order", saved.getId().toString(), "OrderStatusChanged",
                Map.of("orderId", saved.getId(), "from", previous.name(), "to", OrderStatus.CANCELLED.name()));
        }
        if (slaNewlyBreached) {
            publishSlaBreachedEvent(saved);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", saved.getId());
        payload.put("userId", saved.getUserId());
        if (saved.getPaymentId() != null) {
            payload.put("paymentId", saved.getPaymentId());
        }
        payload.put("totalCents", saved.getTotalCents());
        putWalletRefundFields(payload, saved, reason);
        payload.put("currency", saved.getCurrency());
        payload.put("reason", reason);
        payload.put("reasonCode", resolveReasonCode(reason, CancellationReason.CUSTOMER_REQUEST));
        payload.put("reasonDescription", effectiveReason);
        outboxService.publish("Order", saved.getId().toString(), "OrderCancelled", payload);
    }

    void publishStatusTransitionEffects(Order saved,
                                        OrderStatus previous,
                                        OrderStatus newStatus,
                                        String note,
                                        UUID actorId,
                                        Instant now,
                                        boolean slaNewlyBreached) {
        if (newStatus == OrderStatus.PLACED) {
            auditLogService.log(saved.getUserId(), "ORDER_PLACED", "Order", saved.getId().toString(),
                Map.of("totalCents", saved.getTotalCents(), "currency", saved.getCurrency()));
        } else if (newStatus == OrderStatus.FAILED) {
            auditLogService.log(actorId, "ORDER_FAILED", "Order", saved.getId().toString(),
                Map.of("orderUserId", saved.getUserId(), "reason", note != null ? note : ""));
        } else if (newStatus == OrderStatus.REFUNDED) {
            String refundReason = note != null ? note : "Order refunded";
            auditLogService.log(actorId, "ORDER_REFUNDED", "Order", saved.getId().toString(),
                Map.of("orderUserId", saved.getUserId(), "reason", refundReason));
        }

        outboxService.publish("Order", saved.getId().toString(), "OrderStatusChanged",
            Map.of("orderId", saved.getId(), "from", previous.name(), "to", newStatus.name()));
        if (slaNewlyBreached) {
            publishSlaBreachedEvent(saved);
        }

        if (newStatus == OrderStatus.PLACED) {
            outboxService.publish("Order", saved.getId().toString(), "OrderPlaced", buildOrderPlacedPayload(saved));
        } else if (newStatus == OrderStatus.FAILED) {
            Map<String, Object> failedPayload = new LinkedHashMap<>();
            failedPayload.put("orderId", saved.getId());
            failedPayload.put("userId", saved.getUserId());
            if (saved.getPaymentId() != null) {
                failedPayload.put("paymentId", saved.getPaymentId());
            }
            failedPayload.put("reason", note != null ? note : "Fulfillment failure");
            putWalletRefundFields(failedPayload, saved, note);
            outboxService.publish("Order", saved.getId().toString(), "OrderFailed", failedPayload);
        } else if (newStatus == OrderStatus.REFUNDED) {
            Map<String, Object> refundedPayload = new LinkedHashMap<>();
            refundedPayload.put("orderId", saved.getId());
            refundedPayload.put("userId", saved.getUserId());
            if (saved.getPaymentId() != null) {
                refundedPayload.put("paymentId", saved.getPaymentId());
            }
            refundedPayload.put("totalCents", saved.getTotalCents());
            refundedPayload.put("currency", saved.getCurrency());
            refundedPayload.put("reason", note != null ? note : "Order refunded");
            putWalletRefundFields(refundedPayload, saved, note);
            outboxService.publish("Order", saved.getId().toString(), "OrderRefunded", refundedPayload);
        } else if (newStatus == OrderStatus.DELIVERED) {
            Map<String, Object> deliveredPayload = new LinkedHashMap<>();
            deliveredPayload.put("orderId", saved.getId().toString());
            deliveredPayload.put("userId", saved.getUserId().toString());
            if (saved.getPaymentId() != null) {
                deliveredPayload.put("paymentId", saved.getPaymentId().toString());
            }
            deliveredPayload.put("totalCents", saved.getTotalCents());
            deliveredPayload.put("currency", saved.getCurrency());
            deliveredPayload.put("deliveredAt",
                (saved.getDeliveredAt() != null ? saved.getDeliveredAt() : now).toString());
            deliveredPayload.put("slaBreached", saved.isSlaBreached());
            if (saved.getSlaDeadlineAt() != null) {
                deliveredPayload.put("slaDeadlineAt", saved.getSlaDeadlineAt().toString());
            }
            if (saved.getSlaBreachedAt() != null) {
                deliveredPayload.put("slaBreachedAt", saved.getSlaBreachedAt().toString());
            }
            outboxService.publish("Order", saved.getId().toString(), "OrderDelivered", deliveredPayload);
        }
    }

    void publishSlaBreachedEvent(Order order) {
        Instant deadline = order.getSlaDeadlineAt();
        Instant breachedAt = order.getSlaBreachedAt();
        long breachSeconds = (deadline != null && breachedAt != null)
            ? Math.max(0L, Duration.between(deadline, breachedAt).getSeconds())
            : 0L;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.getId().toString());
        payload.put("userId", order.getUserId().toString());
        payload.put("status", order.getStatus().name());
        if (deadline != null) {
            payload.put("slaDeadlineAt", deadline.toString());
        }
        if (breachedAt != null) {
            payload.put("slaBreachedAt", breachedAt.toString());
        }
        payload.put("breachSeconds", breachSeconds);
        outboxService.publish("Order", order.getId().toString(), "OrderSlaBreached", payload);
    }

    private void putWalletRefundFields(Map<String, Object> payload, Order order, String reason) {
        long walletAmount = Math.max(0L, order.getWalletAmountCents());
        boolean sagaRollback = SAGA_ROLLBACK_REASON.equals(reason);
        long walletRefund = sagaRollback ? 0L : Math.min(walletAmount, Math.max(0L, order.getTotalCents()));
        long refundedCents = sagaRollback ? 0L : Math.max(0L, order.getTotalCents());
        payload.put("walletAmountCents", walletAmount);
        payload.put("walletRefundCents", walletRefund);
        payload.put("refundedCents", refundedCents);
    }

    private Map<String, Object> buildOrderPlacedPayload(Order order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.getId());
        payload.put("userId", order.getUserId());
        payload.put("storeId", order.getStoreId());
        if (order.getPaymentId() != null) {
            payload.put("paymentId", order.getPaymentId());
        }
        payload.put("status", order.getStatus().name());
        payload.put("subtotalCents", order.getSubtotalCents());
        payload.put("discountCents", order.getDiscountCents());
        payload.put("totalCents", order.getTotalCents());
        payload.put("currency", order.getCurrency());
        payload.put("deliveryAddress", order.getDeliveryAddress());
        if (order.getDropoffLat() != null) {
            payload.put("dropoffLat", order.getDropoffLat());
        }
        if (order.getDropoffLng() != null) {
            payload.put("dropoffLng", order.getDropoffLng());
        }
        payload.put("placedAt", order.getUpdatedAt());
        List<OrderItem> items = order.getItems() == null ? List.of() : order.getItems();
        payload.put("items", items.stream().map(this::buildItemPayload).toList());
        return payload;
    }

    private Map<String, Object> buildItemPayload(OrderItem item) {
        Map<String, Object> itemPayload = new LinkedHashMap<>();
        itemPayload.put("productId", item.getProductId());
        itemPayload.put("productName", item.getProductName());
        itemPayload.put("sku", item.getProductSku());
        itemPayload.put("quantity", item.getQuantity());
        itemPayload.put("unitPriceCents", item.getUnitPriceCents());
        itemPayload.put("lineTotalCents", item.getLineTotalCents());
        itemPayload.put("coldChain", item.isColdChain());
        itemPayload.put("fragile", item.isFragile());
        if (item.getStorageTemperature() != null && !item.getStorageTemperature().isBlank()) {
            itemPayload.put("storageTemperature", item.getStorageTemperature());
        }
        itemPayload.put("tareWeightGrams", item.getTareWeightGrams());
        return itemPayload;
    }

    private String resolveReasonCode(String reason, CancellationReason defaultReason) {
        if (reason == null || reason.isBlank()) {
            return defaultReason.name();
        }
        String upper = reason.toUpperCase(Locale.ROOT);
        for (CancellationReason code : CancellationReason.values()) {
            if (upper.contains(code.name())) {
                return code.name();
            }
        }
        if (upper.contains("CUSTOMER") || upper.contains("USER") || upper.contains("MIND")) {
            return CancellationReason.CUSTOMER_REQUEST.name();
        }
        if (upper.contains("PAYMENT") || upper.contains("DECLINE") || upper.contains("CARD")) {
            return CancellationReason.PAYMENT_FAILED.name();
        }
        if (upper.contains("STOCK") || upper.contains("INVENTORY") || upper.contains("SHORTAGE")) {
            return CancellationReason.INVENTORY_SHORTAGE.name();
        }
        if (upper.contains("ETA") || upper.contains("DELAY") || upper.contains("LATE")) {
            return CancellationReason.ETA_UNACCEPTABLE.name();
        }
        if (upper.contains("FRAUD") || upper.contains("RISK")) {
            return CancellationReason.FRAUD_SUSPECTED.name();
        }
        return defaultReason.name();
    }
}
