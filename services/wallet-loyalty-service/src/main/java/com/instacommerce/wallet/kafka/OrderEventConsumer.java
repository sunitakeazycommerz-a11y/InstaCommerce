package com.instacommerce.wallet.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.contracts.topics.TopicNames;
import com.instacommerce.wallet.client.OrderLookupClient;
import com.instacommerce.wallet.client.OrderSnapshot;
import com.instacommerce.wallet.domain.model.WalletTransaction.ReferenceType;
import com.instacommerce.wallet.service.LoyaltyService;
import com.instacommerce.wallet.service.WalletService;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final LoyaltyService loyaltyService;
    private final WalletService walletService;
    private final OrderLookupClient orderLookupClient;
    private final ObjectMapper objectMapper;

    public OrderEventConsumer(LoyaltyService loyaltyService,
                              WalletService walletService,
                              OrderLookupClient orderLookupClient,
                              ObjectMapper objectMapper) {
        this.loyaltyService = loyaltyService;
        this.walletService = walletService;
        this.orderLookupClient = orderLookupClient;
        this.objectMapper = objectMapper;
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

        loyaltyService.earnPoints(
            userId,
            orderId.toString(),
            orderTotalCents
        );

        // Award 2% cashback on delivered orders
        long cashbackCents = orderTotalCents * 2 / 100;
        if (cashbackCents > 0) {
            walletService.credit(
                userId,
                cashbackCents,
                ReferenceType.CASHBACK,
                "cashback-" + orderId,
                "Cashback on order " + orderId
            );
        }

        log.info("Processed OrderDelivered: orderId={}, userId={}, totalCents={}, cashbackCents={}",
            orderId, userId, orderTotalCents, cashbackCents);
    }
}
