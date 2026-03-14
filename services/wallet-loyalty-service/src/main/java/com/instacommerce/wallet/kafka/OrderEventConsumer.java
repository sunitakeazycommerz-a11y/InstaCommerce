package com.instacommerce.wallet.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.contracts.topics.TopicNames;
import com.instacommerce.wallet.domain.model.WalletTransaction.ReferenceType;
import com.instacommerce.wallet.service.LoyaltyService;
import com.instacommerce.wallet.service.WalletService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final LoyaltyService loyaltyService;
    private final WalletService walletService;
    private final ObjectMapper objectMapper;

    public OrderEventConsumer(LoyaltyService loyaltyService, WalletService walletService,
                              ObjectMapper objectMapper) {
        this.loyaltyService = loyaltyService;
        this.walletService = walletService;
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
        String userId = event.path("userId").asText();
        String orderId = event.path("orderId").asText();
        long orderTotalCents = event.path("totalCents").asLong();

        loyaltyService.earnPoints(
            java.util.UUID.fromString(userId),
            orderId,
            orderTotalCents
        );

        // Award 2% cashback on delivered orders
        long cashbackCents = orderTotalCents * 2 / 100;
        if (cashbackCents > 0) {
            walletService.credit(
                java.util.UUID.fromString(userId),
                cashbackCents,
                ReferenceType.CASHBACK,
                "cashback-" + orderId,
                "Cashback on order " + orderId
            );
        }

        log.info("Processed OrderDelivered: orderId={}, userId={}", orderId, userId);
    }
}
