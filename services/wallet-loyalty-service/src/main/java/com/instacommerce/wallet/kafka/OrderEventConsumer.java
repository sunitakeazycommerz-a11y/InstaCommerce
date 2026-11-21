package com.instacommerce.wallet.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.contracts.topics.TopicNames;
import com.instacommerce.wallet.client.OrderLookupClient;
import com.instacommerce.wallet.client.OrderSnapshot;
import com.instacommerce.wallet.config.WalletProperties;
import com.instacommerce.wallet.domain.model.WalletTransaction.ReferenceType;
import com.instacommerce.wallet.exception.DuplicateTransactionException;
import com.instacommerce.wallet.service.LoyaltyService;
import com.instacommerce.wallet.service.WalletService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);
    private static final String SAGA_ROLLBACK_REASON = "CHECKOUT_SAGA_ROLLBACK";

    private final LoyaltyService loyaltyService;
    private final WalletService walletService;
    private final OrderLookupClient orderLookupClient;
    private final ObjectMapper objectMapper;
    private final WalletProperties walletProperties;

    public OrderEventConsumer(LoyaltyService loyaltyService,
                              WalletService walletService,
                              OrderLookupClient orderLookupClient,
                              ObjectMapper objectMapper,
                              WalletProperties walletProperties) {
        this.loyaltyService = loyaltyService;
        this.walletService = walletService;
        this.orderLookupClient = orderLookupClient;
        this.objectMapper = objectMapper;
        this.walletProperties = walletProperties != null ? walletProperties : new WalletProperties();
    }

    @KafkaListener(topics = TopicNames.ORDERS_EVENTS, groupId = "wallet-loyalty-service")
    public void consume(String message) throws JsonProcessingException {
        JsonNode event = objectMapper.readTree(message);
        String eventType = event.path("eventType").asText();

        switch (eventType) {
            case "OrderDelivered" -> handleOrderDelivered(event);
            case "OrderCancelled", "OrderFailed", "OrderRefunded" -> handleTerminalOrderRefund(event, eventType);
            case "OrderPartiallyRefunded" -> handlePartialOrderRefund(event);
            default -> log.debug("Ignoring order event type={}", eventType);
        }
    }

    private void handleOrderDelivered(JsonNode event) {
        JsonNode payload = extractPayload(event);
        UUID orderId = parseOrderId(payload, "OrderDelivered");
        if (orderId == null) {
            return;
        }

        long orderTotalCents = payload.path("totalCents").asLong(0);
        UUID userId = parseOptionalUuid(payload, "userId", "OrderDelivered");

        if (userId == null || orderTotalCents <= 0) {
            Optional<OrderSnapshot> snapshot = orderLookupClient.findOrder(orderId);
            if (snapshot.isPresent()) {
                if (userId == null) {
                    userId = snapshot.get().userId();
                }
                if (orderTotalCents <= 0) {
                    orderTotalCents = snapshot.get().totalCents();
                }
            }
        }

        if (userId == null) {
            log.warn("Could not resolve userId for delivered order {}, skipping points and cashback", orderId);
            return;
        }

        try {
            loyaltyService.earnPoints(userId, orderId.toString(), orderTotalCents);
        } catch (DuplicateTransactionException ex) {
            log.info("Duplicate loyalty points earn treated as idempotent success: orderId={}, userId={}",
                orderId, userId);
        }

        long cashbackCents = computeEligibleCashbackCents(userId, orderTotalCents);
        if (cashbackCents > 0) {
            creditIdempotently(
                userId,
                cashbackCents,
                ReferenceType.CASHBACK,
                "cashback-" + orderId,
                "Cashback on order " + orderId,
                orderId);
        }

        log.info("Processed OrderDelivered: orderId={}, userId={}, totalCents={}, cashbackCents={}",
            orderId, userId, orderTotalCents, cashbackCents);
    }

    private void handleTerminalOrderRefund(JsonNode event, String eventType) {
        JsonNode payload = extractPayload(event);
        UUID orderId = parseOrderId(payload, eventType);
        if (orderId == null) {
            return;
        }

        String reason = textOrNull(payload, "reason");
        if (SAGA_ROLLBACK_REASON.equals(reason)) {
            log.debug("Skipping wallet refund for {} event on order {} owned by checkout saga rollback",
                eventType, orderId);
            return;
        }

        long walletAmountCents = Math.max(0L, payload.path("walletAmountCents").asLong(0L));
        long refundAmountCents = payload.hasNonNull("walletRefundCents")
            ? payload.path("walletRefundCents").asLong(0L)
            : walletAmountCents;
        if (walletAmountCents > 0) {
            refundAmountCents = Math.min(refundAmountCents, walletAmountCents);
        }
        if (refundAmountCents <= 0) {
            return;
        }

        UUID userId = resolveUserId(payload, orderId, eventType);
        if (userId == null) {
            log.warn("Could not resolve userId for {} order {}, skipping wallet refund", eventType, orderId);
            return;
        }

        creditIdempotently(
            userId,
            refundAmountCents,
            ReferenceType.REFUND,
            "wallet-refund-" + orderId,
            "Wallet refund for " + eventType + " on order " + orderId,
            orderId);
        log.info("Processed {} wallet refund: orderId={}, userId={}, amountCents={}",
            eventType, orderId, userId, refundAmountCents);
    }

    private void handlePartialOrderRefund(JsonNode event) {
        JsonNode payload = extractPayload(event);
        UUID orderId = parseOrderId(payload, "OrderPartiallyRefunded");
        if (orderId == null) {
            return;
        }

        long walletAmountCents = Math.max(0L, payload.path("walletAmountCents").asLong(0L));
        long walletRefundCents;
        if (payload.hasNonNull("walletRefundCents")) {
            walletRefundCents = payload.path("walletRefundCents").asLong(0L);
        } else if (walletAmountCents > 0 && textOrNull(payload, "paymentId") == null) {
            walletRefundCents = Math.min(walletAmountCents, Math.max(0L, payload.path("refundCents").asLong(0L)));
        } else {
            walletRefundCents = 0L;
        }
        if (walletAmountCents > 0) {
            walletRefundCents = Math.min(walletRefundCents, walletAmountCents);
        }
        if (walletRefundCents <= 0) {
            return;
        }

        UUID userId = resolveUserId(payload, orderId, "OrderPartiallyRefunded");
        if (userId == null) {
            log.warn("Could not resolve userId for OrderPartiallyRefunded order {}, skipping wallet refund", orderId);
            return;
        }

        String refId = buildPartialRefundRefId(event, payload, orderId, walletRefundCents);
        creditIdempotently(
            userId,
            walletRefundCents,
            ReferenceType.REFUND,
            refId,
            "Partial wallet refund on order " + orderId,
            orderId);
        log.info("Processed OrderPartiallyRefunded wallet refund: orderId={}, userId={}, amountCents={}",
            orderId, userId, walletRefundCents);
    }

    private void creditIdempotently(UUID userId,
                                    long amountCents,
                                    ReferenceType refType,
                                    String referenceId,
                                    String description,
                                    UUID orderId) {
        try {
            walletService.credit(userId, amountCents, refType, referenceId, description);
        } catch (DuplicateTransactionException ex) {
            log.info("Duplicate {} credit treated as idempotent success: refId={}, orderId={}, userId={}",
                refType, referenceId, orderId, userId);
        }
    }

    private UUID resolveUserId(JsonNode payload, UUID orderId, String eventType) {
        UUID userId = parseOptionalUuid(payload, "userId", eventType);
        if (userId != null) {
            return userId;
        }
        return orderLookupClient.findOrder(orderId)
            .map(OrderSnapshot::userId)
            .orElse(null);
    }

    private String buildPartialRefundRefId(JsonNode event, JsonNode payload, UUID orderId, long walletRefundCents) {
        String eventId = textOrNull(event, "id");
        if (eventId != null) {
            return "wallet-partial-refund-" + orderId + "-" + eventId;
        }
        String fingerprint = orderId + ":" + walletRefundCents + ":"
            + payload.path("priceDeltaCents").asLong(0L) + ":" + payload.path("reason").asText("");
        return "wallet-partial-refund-" + orderId + "-"
            + UUID.nameUUIDFromBytes(fingerprint.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonNode extractPayload(JsonNode event) {
        return event.has("payload") ? event.path("payload") : event;
    }

    private UUID parseOrderId(JsonNode payload, String eventType) {
        String orderIdStr = textOrNull(payload, "orderId");
        if (orderIdStr == null) {
            log.warn("Ignoring {} event without orderId", eventType);
            return null;
        }
        try {
            return UUID.fromString(orderIdStr);
        } catch (IllegalArgumentException ex) {
            log.warn("Invalid orderId {} in {} event", orderIdStr, eventType);
            return null;
        }
    }

    private UUID parseOptionalUuid(JsonNode payload, String field, String eventType) {
        String value = textOrNull(payload, field);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            log.warn("Invalid {} {} in {} event", field, value, eventType);
            return null;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText(null);
        return (text == null || text.isBlank()) ? null : text.trim();
    }

    private long computeEligibleCashbackCents(UUID userId, long orderTotalCents) {
        if (orderTotalCents <= 0) {
            return 0L;
        }
        int rateBps = Math.max(0, walletProperties.getLoyalty().getCashbackRateBps());
        if (rateBps == 0) {
            return 0L;
        }
        long rawCashback = (orderTotalCents / 10_000L) * rateBps
            + ((orderTotalCents % 10_000L) * rateBps) / 10_000L;
        long perOrderCap = walletProperties.getLoyalty().getMaxCashbackPerOrderCents();
        long capped = perOrderCap > 0 ? Math.min(rawCashback, perOrderCap) : rawCashback;
        if (capped <= 0) {
            return 0L;
        }
        long monthlyCap = walletProperties.getLoyalty().getMonthlyCashbackCapCents();
        if (monthlyCap > 0) {
            Instant monthStart = ZonedDateTime.now(ZoneOffset.UTC)
                .withDayOfMonth(1)
                .toLocalDate()
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
            long earnedThisMonth = walletService.getMonthlyCashbackCreditedCents(userId, monthStart);
            long remainingAllowance = Math.max(0L, monthlyCap - earnedThisMonth);
            capped = Math.min(capped, remainingAllowance);
        }
        return Math.max(0L, capped);
    }
}
