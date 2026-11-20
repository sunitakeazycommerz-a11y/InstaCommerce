package com.instacommerce.wallet.kafka;

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
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final LoyaltyService loyaltyService;
    private final WalletService walletService;
    private final OrderLookupClient orderLookupClient;
    private final ObjectMapper objectMapper;
    private final WalletProperties walletProperties;

    public OrderEventConsumer(LoyaltyService loyaltyService,
                              WalletService walletService,
                              OrderLookupClient orderLookupClient,
                              ObjectMapper objectMapper) {
        this(loyaltyService, walletService, orderLookupClient, objectMapper, new WalletProperties());
    }

    @Autowired
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
    public void consume(String message) {
        try {
            JsonNode event = objectMapper.readTree(message);
            String eventType = event.path("eventType").asText();

            if ("OrderDelivered".equals(eventType)) {
                handleOrderDelivered(event);
            }
        } catch (Exception ex) {
            log.error("Failed to process order event: {}", message, ex);
        }
    }

    private void handleOrderDelivered(JsonNode event) {
        JsonNode payload = event.has("payload") ? event.path("payload") : event;
        String userIdStr = payload.path("userId").asText(null);
        String orderIdStr = payload.path("orderId").asText(null);
        if (orderIdStr == null || orderIdStr.isBlank()) {
            log.warn("Ignoring OrderDelivered event without orderId");
            return;
        }

        UUID orderId;
        try {
            orderId = UUID.fromString(orderIdStr);
        } catch (IllegalArgumentException ex) {
            log.warn("Invalid orderId {} in OrderDelivered event", orderIdStr);
            return;
        }

        long orderTotalCents = payload.path("totalCents").asLong(0);
        UUID userId = null;
        if (userIdStr != null && !userIdStr.isBlank()) {
            try {
                userId = UUID.fromString(userIdStr);
            } catch (IllegalArgumentException ex) {
                log.warn("Invalid userId {} in OrderDelivered event", userIdStr);
            }
        }

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
            loyaltyService.earnPoints(
                userId,
                orderId.toString(),
                orderTotalCents
            );
        } catch (DuplicateTransactionException ex) {
            log.info("Duplicate loyalty points earn treated as idempotent success: orderId={}, userId={}",
                orderId, userId);
        }

        long cashbackCents = computeEligibleCashbackCents(userId, orderTotalCents);
        if (cashbackCents > 0) {
            try {
                walletService.credit(
                    userId,
                    cashbackCents,
                    ReferenceType.CASHBACK,
                    "cashback-" + orderId,
                    "Cashback on order " + orderId
                );
            } catch (DuplicateTransactionException ex) {
                log.info("Duplicate cashback credit treated as idempotent success: orderId={}, userId={}",
                    orderId, userId);
            }
        }

        log.info("Processed OrderDelivered: orderId={}, userId={}, totalCents={}, cashbackCents={}",
            orderId, userId, orderTotalCents, cashbackCents);
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
