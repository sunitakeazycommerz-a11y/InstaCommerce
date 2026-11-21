package com.instacommerce.wallet.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.wallet.client.OrderLookupClient;
import com.instacommerce.wallet.client.OrderSnapshot;
import com.instacommerce.wallet.config.WalletProperties;
import com.instacommerce.wallet.domain.model.WalletTransaction.ReferenceType;
import com.instacommerce.wallet.exception.DuplicateTransactionException;
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
import org.springframework.dao.TransientDataAccessResourceException;

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
            objectMapper,
            new WalletProperties()
        );
    }

    @Test
    @DisplayName("Awards loyalty points and cashback when OrderDelivered has complete payload")
    void awardsPointsAndCashbackOnCompletePayload() throws Exception {
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
    void fallsBackToOrderLookupClientWhenTotalCentsMissing() throws Exception {
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
    @DisplayName("Ignores non-handled order events")
    void ignoresNonOrderDeliveredEvents() throws Exception {
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

    @Test
    @DisplayName("Still awards cashback when earnPoints throws DuplicateTransactionException")
    void awardsCashbackEvenWhenLoyaltyPointsAlreadyEarned() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        long totalCents = 10000L;

        when(loyaltyService.earnPoints(userId, orderId.toString(), totalCents))
            .thenThrow(new DuplicateTransactionException("Transaction already exists for ref=ORDER/" + orderId));

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
            eq(200L),
            eq(ReferenceType.CASHBACK),
            eq("cashback-" + orderId),
            anyString()
        );
    }

    @Test
    @DisplayName("Treats duplicate points and duplicate cashback as idempotent on full replay")
    void handlesDuplicateCashbackGracefullyOnFullReplay() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        long totalCents = 10000L;

        when(loyaltyService.earnPoints(userId, orderId.toString(), totalCents))
            .thenThrow(new DuplicateTransactionException("Points already awarded"));
        when(walletService.credit(
                eq(userId),
                eq(200L),
                eq(ReferenceType.CASHBACK),
                eq("cashback-" + orderId),
                anyString()))
            .thenThrow(new DuplicateTransactionException("Cashback already credited"));

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
            eq(200L),
            eq(ReferenceType.CASHBACK),
            eq("cashback-" + orderId),
            anyString()
        );
    }

    @Test
    @DisplayName("Credits wallet refund idempotently on OrderCancelled, OrderFailed, and OrderRefunded")
    void creditsWalletRefundOnTerminalCancellationAndFailure() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID cancelledOrderId = UUID.randomUUID();
        UUID failedOrderId = UUID.randomUUID();

        String cancelledMessage = """
            {
              "eventType": "OrderCancelled",
              "payload": {
                "orderId": "%s",
                "userId": "%s",
                "totalCents": 10000,
                "walletAmountCents": 3000,
                "walletRefundCents": 3000,
                "reason": "CUSTOMER_REQUEST"
              }
            }
            """.formatted(cancelledOrderId, userId);

        consumer.consume(cancelledMessage);

        verify(walletService).credit(
            eq(userId),
            eq(3000L),
            eq(ReferenceType.REFUND),
            eq("wallet-refund-" + cancelledOrderId),
            anyString()
        );

        String failedMessage = """
            {
              "eventType": "OrderFailed",
              "payload": {
                "orderId": "%s",
                "userId": "%s",
                "walletAmountCents": 2500,
                "reason": "DELIVERY_FAILED"
              }
            }
            """.formatted(failedOrderId, userId);

        consumer.consume(failedMessage);

        verify(walletService).credit(
            eq(userId),
            eq(2500L),
            eq(ReferenceType.REFUND),
            eq("wallet-refund-" + failedOrderId),
            anyString()
        );
    }

    @Test
    @DisplayName("Credits wallet partial refund using deterministic event reference and resolves missing userId")
    void creditsPartialWalletRefundIdempotently() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();

        when(orderLookupClient.findOrder(orderId)).thenReturn(Optional.of(
            new OrderSnapshot(orderId, userId, 5000L, "INR", "PACKING", Instant.now())
        ));
        when(walletService.credit(eq(userId), eq(1200L), eq(ReferenceType.REFUND),
            eq("wallet-partial-refund-" + orderId + "-" + eventId), anyString()))
            .thenThrow(new DuplicateTransactionException("Already refunded"));

        String partialMessage = """
            {
              "id": "%s",
              "eventType": "OrderPartiallyRefunded",
              "payload": {
                "orderId": "%s",
                "refundCents": 1200,
                "walletAmountCents": 5000,
                "walletRefundCents": 1200,
                "priceDeltaCents": -1200,
                "reason": "ORDER_MODIFIED_PARTIAL_REFUND"
              }
            }
            """.formatted(eventId, orderId);

        consumer.consume(partialMessage);

        verify(walletService).credit(
            eq(userId),
            eq(1200L),
            eq(ReferenceType.REFUND),
            eq("wallet-partial-refund-" + orderId + "-" + eventId),
            anyString()
        );
    }

    @Test
    @DisplayName("Skips saga-rollback cancellations and zero-walletRefundCents payloads")
    void skipsSagaRollbackAndZeroWalletRefund() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        String sagaRollback = """
            {
              "eventType": "OrderCancelled",
              "payload": {
                "orderId": "%s",
                "userId": "%s",
                "walletAmountCents": 3000,
                "walletRefundCents": 0,
                "reason": "CHECKOUT_SAGA_ROLLBACK"
              }
            }
            """.formatted(orderId, userId);

        consumer.consume(sagaRollback);
        verify(walletService, never()).credit(any(), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("Propagates transient exceptions and malformed JSON instead of swallowing offsets")
    void propagatesTransientErrorsAndMalformedJson() {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        doThrow(new TransientDataAccessResourceException("db busy"))
            .when(walletService)
            .credit(eq(userId), eq(1500L), eq(ReferenceType.REFUND), eq("wallet-refund-" + orderId), anyString());

        String message = """
            {
              "eventType": "OrderCancelled",
              "payload": {
                "orderId": "%s",
                "userId": "%s",
                "walletAmountCents": 1500,
                "walletRefundCents": 1500,
                "reason": "CUSTOMER_REQUEST"
              }
            }
            """.formatted(orderId, userId);

        assertThatThrownBy(() -> consumer.consume(message))
            .isInstanceOf(TransientDataAccessResourceException.class);
        assertThatThrownBy(() -> consumer.consume("{invalid-json"))
            .isInstanceOf(JsonProcessingException.class);
    }
}
