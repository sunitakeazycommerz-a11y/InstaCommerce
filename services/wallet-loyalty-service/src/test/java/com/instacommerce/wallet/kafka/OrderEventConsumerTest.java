package com.instacommerce.wallet.kafka;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.wallet.client.OrderLookupClient;
import com.instacommerce.wallet.client.OrderSnapshot;
import com.instacommerce.wallet.domain.model.WalletTransaction.ReferenceType;
import com.instacommerce.wallet.service.LoyaltyService;
import com.instacommerce.wallet.service.WalletService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderEventConsumerTest {

    @Mock
    private LoyaltyService loyaltyService;

    @Mock
    private WalletService walletService;

    @Mock
    private OrderLookupClient orderLookupClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OrderEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new OrderEventConsumer(
            loyaltyService,
            walletService,
            orderLookupClient,
            objectMapper
        );
    }

    @Test
    @DisplayName("Awards loyalty points and cashback when OrderDelivered has complete payload")
    void awardsPointsAndCashbackOnCompletePayload() {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        long totalCents = 10000L;

        String message = """
            {
              "eventType": "OrderDelivered",
              "payload": {
                "orderId": "%s",
                "userId": "%s",
                "totalCents": %d
              }
            }
            """.formatted(orderId, userId, totalCents);

        consumer.consume(message);

        verify(loyaltyService).earnPoints(userId, orderId.toString(), totalCents);
        verify(walletService).credit(
            eq(userId),
            eq(200L), // 2% of 10000
            eq(ReferenceType.CASHBACK),
            eq("cashback-" + orderId),
            anyString()
        );
    }

    @Test
    @DisplayName("Falls back to OrderLookupClient when totalCents is missing in OrderDelivered event")
    void fallsBackToOrderLookupClientWhenTotalCentsMissing() {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        long resolvedTotalCents = 15000L;

        when(orderLookupClient.findOrder(orderId)).thenReturn(Optional.of(
            new OrderSnapshot(orderId, userId, resolvedTotalCents, "USD", "DELIVERED", Instant.now())
        ));

        String message = """
            {
              "eventType": "OrderDelivered",
              "payload": {
                "orderId": "%s",
                "deliveredAt": "2026-10-17T12:00:00Z"
              }
            }
            """.formatted(orderId);

        consumer.consume(message);

        verify(orderLookupClient).findOrder(orderId);
        verify(loyaltyService).earnPoints(userId, orderId.toString(), resolvedTotalCents);
        verify(walletService).credit(
            eq(userId),
            eq(300L), // 2% of 15000
            eq(ReferenceType.CASHBACK),
            eq("cashback-" + orderId),
            anyString()
        );
    }

    @Test
    @DisplayName("Ignores non-OrderDelivered events")
    void ignoresNonOrderDeliveredEvents() {
        String message = """
            {
              "eventType": "OrderPlaced",
              "payload": {
                "orderId": "%s"
              }
            }
            """.formatted(UUID.randomUUID());

        consumer.consume(message);

        verify(loyaltyService, never()).earnPoints(any(), any(), anyLong());
        verify(walletService, never()).credit(any(), anyLong(), any(), any(), any());
    }
}
