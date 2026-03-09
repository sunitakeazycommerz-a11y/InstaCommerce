package com.instacommerce.wallet.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.wallet.client.OrderLookupClient;
import com.instacommerce.wallet.client.OrderSnapshot;
import com.instacommerce.wallet.domain.model.WalletTransaction.ReferenceType;
import com.instacommerce.wallet.dto.response.WalletTransactionResponse;
import com.instacommerce.wallet.exception.DuplicateTransactionException;
import com.instacommerce.wallet.exception.OrderNotFoundException;
import com.instacommerce.wallet.service.WalletService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentEventConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private WalletService walletService;
    @Mock private OrderLookupClient orderLookupClient;

    private PaymentEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new PaymentEventConsumer(walletService, orderLookupClient, objectMapper);
    }

    // --- Envelope parsing ---

    @Nested
    @DisplayName("Envelope parsing and filtering")
    class EnvelopeParsing {

        @Test
        @DisplayName("Ignores non-PaymentRefunded events")
        void ignoresNonPaymentRefundedEvents() throws Exception {
            String json = objectMapper.writeValueAsString(Map.of(
                "eventType", "PaymentCaptured",
                "payload", Map.of("paymentId", UUID.randomUUID().toString())));

            consumer.consume(json);

            verifyNoInteractions(walletService, orderLookupClient);
        }

        @Test
        @DisplayName("Throws on malformed JSON for retry/DLT")
        void throwsOnMalformedJson() {
            assertThatThrownBy(() -> consumer.consume("not-json"))
                .isInstanceOf(Exception.class);

            verifyNoInteractions(walletService, orderLookupClient);
        }
    }

    // --- Correct field mapping ---

    @Nested
    @DisplayName("Payload field mapping")
    class FieldMapping {

        private final UUID refundId = UUID.randomUUID();
        private final UUID paymentId = UUID.randomUUID();
        private final UUID orderId = UUID.randomUUID();
        private final UUID userId = UUID.randomUUID();
        private final long amountCents = 4999;

        @Test
        @DisplayName("Reads correct fields from nested payload and resolves userId via order lookup")
        void readsCorrectFieldsFromNestedPayload() throws Exception {
            OrderSnapshot order = new OrderSnapshot(orderId, userId, 10000, "INR", "DELIVERED", Instant.now());
            when(orderLookupClient.findOrder(orderId)).thenReturn(Optional.of(order));
            when(walletService.credit(any(), anyLong(), any(), anyString(), anyString()))
                .thenReturn(dummyResponse());

            firePaymentRefundedEvent(refundId, paymentId, orderId, amountCents);

            ArgumentCaptor<UUID> userCaptor = ArgumentCaptor.forClass(UUID.class);
            ArgumentCaptor<Long> amountCaptor = ArgumentCaptor.forClass(Long.class);
            ArgumentCaptor<String> refIdCaptor = ArgumentCaptor.forClass(String.class);

            verify(walletService).credit(
                userCaptor.capture(), amountCaptor.capture(),
                eq(ReferenceType.REFUND), refIdCaptor.capture(), anyString());

            assertThat(userCaptor.getValue()).isEqualTo(userId);
            assertThat(amountCaptor.getValue()).isEqualTo(amountCents);
            assertThat(refIdCaptor.getValue()).isEqualTo(refundId.toString());
        }

        @Test
        @DisplayName("Reads amountCents, not refundAmountCents")
        void readsAmountCentsNotRefundAmountCents() throws Exception {
            OrderSnapshot order = new OrderSnapshot(orderId, userId, 10000, "INR", "DELIVERED", Instant.now());
            when(orderLookupClient.findOrder(orderId)).thenReturn(Optional.of(order));
            when(walletService.credit(any(), anyLong(), any(), anyString(), anyString()))
                .thenReturn(dummyResponse());

            // Event with only amountCents (no refundAmountCents)
            firePaymentRefundedEvent(refundId, paymentId, orderId, 7500);

            ArgumentCaptor<Long> amountCaptor = ArgumentCaptor.forClass(Long.class);
            verify(walletService).credit(any(), amountCaptor.capture(), any(), anyString(), anyString());
            assertThat(amountCaptor.getValue()).isEqualTo(7500);
        }

        @Test
        @DisplayName("Works with flat event structure (no payload wrapper)")
        void worksWithFlatEventStructure() throws Exception {
            OrderSnapshot order = new OrderSnapshot(orderId, userId, 10000, "INR", "DELIVERED", Instant.now());
            when(orderLookupClient.findOrder(orderId)).thenReturn(Optional.of(order));
            when(walletService.credit(any(), anyLong(), any(), anyString(), anyString()))
                .thenReturn(dummyResponse());

            // Flat structure: fields at root level alongside eventType
            String json = objectMapper.writeValueAsString(Map.of(
                "eventType", "PaymentRefunded",
                "refundId", refundId.toString(),
                "paymentId", paymentId.toString(),
                "orderId", orderId.toString(),
                "amountCents", amountCents,
                "currency", "INR"));

            consumer.consume(json);

            verify(walletService).credit(eq(userId), eq(amountCents),
                eq(ReferenceType.REFUND), eq(refundId.toString()), anyString());
        }
    }

    // --- Idempotency ---

    @Nested
    @DisplayName("Idempotency keyed on refundId")
    class IdempotencyBehavior {

        @Test
        @DisplayName("Uses refundId (not paymentId) as idempotency reference")
        void usesRefundIdAsIdempotencyKey() throws Exception {
            UUID refundId = UUID.randomUUID();
            UUID paymentId = UUID.randomUUID();
            UUID orderId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();

            OrderSnapshot order = new OrderSnapshot(orderId, userId, 10000, "INR", "DELIVERED", Instant.now());
            when(orderLookupClient.findOrder(orderId)).thenReturn(Optional.of(order));
            when(walletService.credit(any(), anyLong(), any(), anyString(), anyString()))
                .thenReturn(dummyResponse());

            firePaymentRefundedEvent(refundId, paymentId, orderId, 5000);

            ArgumentCaptor<String> refIdCaptor = ArgumentCaptor.forClass(String.class);
            verify(walletService).credit(any(), anyLong(), any(), refIdCaptor.capture(), anyString());
            assertThat(refIdCaptor.getValue())
                .isEqualTo(refundId.toString())
                .doesNotContain(paymentId.toString());
        }

        @Test
        @DisplayName("Duplicate refund credit is treated as idempotent success")
        void duplicateRefundCreditTreatedAsIdempotentSuccess() throws Exception {
            UUID refundId = UUID.randomUUID();
            UUID paymentId = UUID.randomUUID();
            UUID orderId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();

            OrderSnapshot order = new OrderSnapshot(orderId, userId, 10000, "INR", "DELIVERED", Instant.now());
            when(orderLookupClient.findOrder(orderId)).thenReturn(Optional.of(order));
            when(walletService.credit(any(), anyLong(), any(), anyString(), anyString()))
                .thenThrow(new DuplicateTransactionException(
                    "Transaction already exists for ref=REFUND/" + refundId));

            assertThatCode(() -> firePaymentRefundedEvent(refundId, paymentId, orderId, 5000))
                .doesNotThrowAnyException();

            verify(walletService).credit(eq(userId), eq(5000L),
                eq(ReferenceType.REFUND), eq(refundId.toString()), anyString());
        }

        @Test
        @DisplayName("Two partial refunds for same payment use different idempotency keys")
        void twoPartialRefundsUseDifferentKeys() throws Exception {
            UUID paymentId = UUID.randomUUID();
            UUID orderId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            UUID refundId1 = UUID.randomUUID();
            UUID refundId2 = UUID.randomUUID();

            OrderSnapshot order = new OrderSnapshot(orderId, userId, 10000, "INR", "DELIVERED", Instant.now());
            when(orderLookupClient.findOrder(orderId)).thenReturn(Optional.of(order));
            when(walletService.credit(any(), anyLong(), any(), anyString(), anyString()))
                .thenReturn(dummyResponse());

            firePaymentRefundedEvent(refundId1, paymentId, orderId, 3000);
            firePaymentRefundedEvent(refundId2, paymentId, orderId, 7000);

            ArgumentCaptor<String> refIdCaptor = ArgumentCaptor.forClass(String.class);
            verify(walletService, org.mockito.Mockito.times(2))
                .credit(any(), anyLong(), any(), refIdCaptor.capture(), anyString());

            assertThat(refIdCaptor.getAllValues())
                .containsExactly(refundId1.toString(), refundId2.toString());
        }
    }

    // --- Error handling ---

    @Nested
    @DisplayName("Error handling surfaces failures")
    class ErrorHandling {

        @Test
        @DisplayName("Throws when refundId is missing")
        void throwsWhenRefundIdMissing() throws Exception {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("paymentId", UUID.randomUUID().toString());
            payload.put("orderId", UUID.randomUUID().toString());
            payload.put("amountCents", 5000);
            payload.put("currency", "INR");

            String json = objectMapper.writeValueAsString(Map.of(
                "eventType", "PaymentRefunded",
                "payload", payload));

            assertThatThrownBy(() -> consumer.consume(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refundId");

            verifyNoInteractions(walletService);
        }

        @Test
        @DisplayName("Throws when orderId is missing")
        void throwsWhenOrderIdMissing() throws Exception {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("refundId", UUID.randomUUID().toString());
            payload.put("paymentId", UUID.randomUUID().toString());
            payload.put("amountCents", 5000);
            payload.put("currency", "INR");

            String json = objectMapper.writeValueAsString(Map.of(
                "eventType", "PaymentRefunded",
                "payload", payload));

            assertThatThrownBy(() -> consumer.consume(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orderId");

            verifyNoInteractions(walletService);
        }

        @Test
        @DisplayName("Throws when amountCents is zero or negative")
        void throwsWhenAmountInvalid() throws Exception {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("refundId", UUID.randomUUID().toString());
            payload.put("paymentId", UUID.randomUUID().toString());
            payload.put("orderId", UUID.randomUUID().toString());
            payload.put("amountCents", 0);
            payload.put("currency", "INR");

            String json = objectMapper.writeValueAsString(Map.of(
                "eventType", "PaymentRefunded",
                "payload", payload));

            assertThatThrownBy(() -> consumer.consume(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amountCents");

            verifyNoInteractions(walletService, orderLookupClient);
        }

        @Test
        @DisplayName("Throws OrderNotFoundException (non-retryable) when order lookup returns empty")
        void throwsOrderNotFoundWhenOrderLookupReturnsEmpty() throws Exception {
            UUID orderId = UUID.randomUUID();
            when(orderLookupClient.findOrder(orderId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> firePaymentRefundedEvent(
                    UUID.randomUUID(), UUID.randomUUID(), orderId, 5000))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessageContaining(orderId.toString());

            verify(walletService, never()).credit(any(), anyLong(), any(), anyString(), anyString());
        }

        @Test
        @DisplayName("Malformed orderId UUID propagates for DLT")
        void malformedOrderIdPropagates() throws Exception {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("refundId", UUID.randomUUID().toString());
            payload.put("paymentId", UUID.randomUUID().toString());
            payload.put("orderId", "not-a-uuid");
            payload.put("amountCents", 5000);
            payload.put("currency", "INR");

            String json = objectMapper.writeValueAsString(Map.of(
                "eventType", "PaymentRefunded",
                "payload", payload));

            assertThatThrownBy(() -> consumer.consume(json))
                .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(walletService);
        }
    }

    // --- Helpers ---

    private void firePaymentRefundedEvent(UUID refundId, UUID paymentId, UUID orderId, long amountCents) throws Exception {
        String json = objectMapper.writeValueAsString(Map.of(
            "eventType", "PaymentRefunded",
            "payload", Map.of(
                "refundId", refundId.toString(),
                "paymentId", paymentId.toString(),
                "orderId", orderId.toString(),
                "amountCents", amountCents,
                "currency", "INR")));

        consumer.consume(json);
    }

    private WalletTransactionResponse dummyResponse() {
        return new WalletTransactionResponse(
            "CREDIT", 5000, 10000, "REFUND",
            UUID.randomUUID().toString(), "test", Instant.now());
    }
}
