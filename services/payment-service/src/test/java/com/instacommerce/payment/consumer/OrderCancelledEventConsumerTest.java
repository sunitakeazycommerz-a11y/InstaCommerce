package com.instacommerce.payment.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.dto.request.RefundRequest;
import com.instacommerce.payment.dto.response.PaymentResponse;
import com.instacommerce.payment.dto.response.RefundResponse;
import com.instacommerce.payment.repository.PaymentRepository;
import com.instacommerce.payment.service.PaymentService;
import com.instacommerce.payment.service.RefundService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderCancelledEventConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentService paymentService;
    @Mock private RefundService refundService;

    private OrderCancelledEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new OrderCancelledEventConsumer(
            objectMapper, paymentRepository, paymentService, refundService);
    }

    // --- Envelope parsing ---

    @Nested
    @DisplayName("Envelope parsing and filtering")
    class EnvelopeParsing {

        @Test
        @DisplayName("Ignores non-OrderCancelled events")
        void ignoresNonOrderCancelledEvents() throws Exception {
            String json = objectMapper.writeValueAsString(Map.of(
                "id", UUID.randomUUID().toString(),
                "aggregateId", UUID.randomUUID().toString(),
                "eventType", "OrderPlaced",
                "payload", Map.of("orderId", UUID.randomUUID().toString())));

            ConsumerRecord<String, String> record = new ConsumerRecord<>("orders.events", 0, 0L, null, json);
            consumer.onOrderEvent(record);

            verifyNoInteractions(paymentService, refundService, paymentRepository);
        }

        @Test
        @DisplayName("Handles malformed JSON gracefully")
        void handlesMalformedJson() {
            ConsumerRecord<String, String> record = new ConsumerRecord<>("orders.events", 0, 0L, null, "not-json");
            assertThatThrownBy(() -> consumer.onOrderEvent(record)).isInstanceOf(Exception.class);

            verifyNoInteractions(paymentRepository, paymentService, refundService);
        }

        @Test
        @DisplayName("Rejects null payload")
        void handlesNullPayload() throws Exception {
            String json = objectMapper.writeValueAsString(Map.of(
                "id", UUID.randomUUID().toString(),
                "aggregateId", UUID.randomUUID().toString(),
                "eventType", "OrderCancelled"));

            ConsumerRecord<String, String> record = new ConsumerRecord<>("orders.events", 0, 0L, null, json);
            assertThatThrownBy(() -> consumer.onOrderEvent(record))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has no payload");

            verifyNoInteractions(paymentRepository, paymentService, refundService);
        }

        @Test
        @DisplayName("Parses eventId alias correctly")
        void parsesEventIdAlias() throws Exception {
            UUID paymentId = UUID.randomUUID();
            UUID orderId = UUID.randomUUID();

            Payment payment = buildPayment(paymentId, orderId, PaymentStatus.VOIDED, 5000, 0, 0);
            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

            String json = objectMapper.writeValueAsString(Map.of(
                "eventId", UUID.randomUUID().toString(),
                "aggregateId", orderId.toString(),
                "eventType", "OrderCancelled",
                "payload", Map.of(
                    "orderId", orderId.toString(),
                    "paymentId", paymentId.toString(),
                    "totalCents", 5000,
                    "currency", "INR",
                    "reason", "customer request")));

            ConsumerRecord<String, String> record = new ConsumerRecord<>("orders.events", 0, 0L, null, json);
            consumer.onOrderEvent(record);

            // VOIDED → skip
            verify(paymentService, never()).voidAuth(any(), any());
            verify(refundService, never()).refund(any(), any());
        }
    }

    // --- Routing by payment state ---

    @Nested
    @DisplayName("Routing by payment state")
    class RoutingByState {

        private final UUID paymentId = UUID.randomUUID();
        private final UUID orderId = UUID.randomUUID();
        private final String idempotencyKey = orderId + "-cancellation-refund";

        @Test
        @DisplayName("AUTHORIZED → voidAuth with deterministic key")
        void authorizedTriggersVoid() throws Exception {
            Payment payment = buildPayment(paymentId, orderId, PaymentStatus.AUTHORIZED, 10000, 0, 0);
            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
            when(paymentService.voidAuth(paymentId, idempotencyKey))
                .thenReturn(buildPaymentResponse(paymentId, PaymentStatus.VOIDED));

            fireOrderCancelledEvent(orderId, paymentId, 10000, "customer request");

            verify(paymentService).voidAuth(paymentId, idempotencyKey);
            verify(refundService, never()).refund(any(), any());
        }

        @Test
        @DisplayName("CAPTURED → full refund with deterministic key")
        void capturedTriggersFullRefund() throws Exception {
            Payment payment = buildPayment(paymentId, orderId, PaymentStatus.CAPTURED, 10000, 10000, 0);
            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
            when(refundService.refund(eq(paymentId), any(RefundRequest.class)))
                .thenReturn(buildRefundResponse());

            fireOrderCancelledEvent(orderId, paymentId, 10000, "customer request");

            ArgumentCaptor<RefundRequest> captor = ArgumentCaptor.forClass(RefundRequest.class);
            verify(refundService).refund(eq(paymentId), captor.capture());
            RefundRequest req = captor.getValue();
            assertThat(req.amountCents()).isEqualTo(10000);
            assertThat(req.reason()).isEqualTo("customer request");
            assertThat(req.idempotencyKey()).isEqualTo(idempotencyKey);
            verify(paymentService, never()).voidAuth(any(), any());
        }

        @Test
        @DisplayName("PARTIALLY_REFUNDED → refunds remaining captured amount")
        void partiallyRefundedTriggersRemainingRefund() throws Exception {
            Payment payment = buildPayment(paymentId, orderId, PaymentStatus.PARTIALLY_REFUNDED, 10000, 10000, 3000);
            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
            when(refundService.refund(eq(paymentId), any(RefundRequest.class)))
                .thenReturn(buildRefundResponse());

            fireOrderCancelledEvent(orderId, paymentId, 10000, "damaged goods");

            ArgumentCaptor<RefundRequest> captor = ArgumentCaptor.forClass(RefundRequest.class);
            verify(refundService).refund(eq(paymentId), captor.capture());
            assertThat(captor.getValue().amountCents()).isEqualTo(7000);
            assertThat(captor.getValue().reason()).isEqualTo("damaged goods");
        }

        @Test
        @DisplayName("VOIDED → skips idempotently")
        void voidedSkipsIdempotently() throws Exception {
            Payment payment = buildPayment(paymentId, orderId, PaymentStatus.VOIDED, 10000, 0, 0);
            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

            fireOrderCancelledEvent(orderId, paymentId, 10000, "dup");

            verify(paymentService, never()).voidAuth(any(), any());
            verify(refundService, never()).refund(any(), any());
        }

        @Test
        @DisplayName("REFUNDED → skips idempotently")
        void refundedSkipsIdempotently() throws Exception {
            Payment payment = buildPayment(paymentId, orderId, PaymentStatus.REFUNDED, 10000, 10000, 10000);
            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

            fireOrderCancelledEvent(orderId, paymentId, 10000, "dup");

            verify(paymentService, never()).voidAuth(any(), any());
            verify(refundService, never()).refund(any(), any());
        }

        @Test
        @DisplayName("AUTHORIZE_PENDING → retried by container and eventually sent to DLT")
        void authorizePendingIsInvalidState() throws Exception {
            Payment payment = buildPayment(paymentId, orderId, PaymentStatus.AUTHORIZE_PENDING, 10000, 0, 0);
            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

            assertThatThrownBy(() -> fireOrderCancelledEvent(orderId, paymentId, 10000, "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pending state");

            verify(paymentService, never()).voidAuth(any(), any());
            verify(refundService, never()).refund(any(), any());
        }

        @Test
        @DisplayName("CAPTURE_PENDING → retried by container and eventually sent to DLT")
        void capturePendingIsInvalidState() throws Exception {
            Payment payment = buildPayment(paymentId, orderId, PaymentStatus.CAPTURE_PENDING, 10000, 0, 0);
            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

            assertThatThrownBy(() -> fireOrderCancelledEvent(orderId, paymentId, 10000, "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pending state");

            verify(paymentService, never()).voidAuth(any(), any());
            verify(refundService, never()).refund(any(), any());
        }

        @Test
        @DisplayName("VOID_PENDING → retried by container and eventually sent to DLT")
        void voidPendingIsInvalidState() throws Exception {
            Payment payment = buildPayment(paymentId, orderId, PaymentStatus.VOID_PENDING, 10000, 0, 0);
            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

            assertThatThrownBy(() -> fireOrderCancelledEvent(orderId, paymentId, 10000, "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pending state");

            verify(paymentService, never()).voidAuth(any(), any());
            verify(refundService, never()).refund(any(), any());
        }

        @Test
        @DisplayName("DISPUTED → skips because dispute must be resolved externally")
        void disputedSkipsIdempotently() throws Exception {
            Payment payment = buildPayment(paymentId, orderId, PaymentStatus.DISPUTED, 10000, 10000, 0);
            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

            fireOrderCancelledEvent(orderId, paymentId, 10000, "test");

            verify(paymentService, never()).voidAuth(any(), any());
            verify(refundService, never()).refund(any(), any());
        }

        @Test
        @DisplayName("FAILED → skips because no recoverable funds remain")
        void failedSkipsIdempotently() throws Exception {
            Payment payment = buildPayment(paymentId, orderId, PaymentStatus.FAILED, 10000, 0, 0);
            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

            fireOrderCancelledEvent(orderId, paymentId, 10000, "test");

            verify(paymentService, never()).voidAuth(any(), any());
            verify(refundService, never()).refund(any(), any());
        }

        @Test
        @DisplayName("CAPTURED with zero refundable amount → skips gracefully")
        void capturedWithZeroRefundableSkips() throws Exception {
            Payment payment = buildPayment(paymentId, orderId, PaymentStatus.CAPTURED, 10000, 10000, 10000);
            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

            fireOrderCancelledEvent(orderId, paymentId, 10000, "test");

            verify(refundService, never()).refund(any(), any());
        }
    }

    // --- Idempotency ---

    @Nested
    @DisplayName("Replay-safe / idempotency behavior")
    class IdempotencyBehavior {

        @Test
        @DisplayName("Deterministic idempotency key is derived from orderId")
        void deterministicIdempotencyKey() throws Exception {
            UUID paymentId = UUID.randomUUID();
            UUID orderId = UUID.randomUUID();
            String expectedKey = orderId + "-cancellation-refund";

            Payment payment = buildPayment(paymentId, orderId, PaymentStatus.AUTHORIZED, 5000, 0, 0);
            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
            when(paymentService.voidAuth(paymentId, expectedKey))
                .thenReturn(buildPaymentResponse(paymentId, PaymentStatus.VOIDED));

            fireOrderCancelledEvent(orderId, paymentId, 5000, "test");

            verify(paymentService).voidAuth(paymentId, expectedKey);
        }

        @Test
        @DisplayName("Replaying same event on VOIDED payment is a no-op")
        void replayOnVoidedIsNoop() throws Exception {
            UUID paymentId = UUID.randomUUID();
            UUID orderId = UUID.randomUUID();

            Payment payment = buildPayment(paymentId, orderId, PaymentStatus.VOIDED, 5000, 0, 0);
            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

            // Fire same event twice
            fireOrderCancelledEvent(orderId, paymentId, 5000, "test");
            fireOrderCancelledEvent(orderId, paymentId, 5000, "test");

            verifyNoInteractions(paymentService, refundService);
        }
    }

    // --- Payment resolution ---

    @Nested
    @DisplayName("Payment ID resolution")
    class PaymentResolution {

        @Test
        @DisplayName("Skips cleanly when the cancellation happened before payment assignment")
        void skipsWhenPaymentIdIsEmpty() throws Exception {
            UUID orderId = UUID.randomUUID();

            String json = objectMapper.writeValueAsString(Map.of(
                "id", UUID.randomUUID().toString(),
                "aggregateId", orderId.toString(),
                "eventType", "OrderCancelled",
                "payload", Map.of(
                    "orderId", orderId.toString(),
                    "paymentId", "",
                    "totalCents", 5000,
                    "currency", "INR",
                    "reason", "test")));

            ConsumerRecord<String, String> record = new ConsumerRecord<>("orders.events", 0, 0L, null, json);
            consumer.onOrderEvent(record);

            verifyNoInteractions(paymentRepository, paymentService, refundService);
        }

        @Test
        @DisplayName("Rejects malformed paymentId so the record can be dead-lettered")
        void rejectsMalformedPaymentId() throws Exception {
            UUID orderId = UUID.randomUUID();

            String json = objectMapper.writeValueAsString(Map.of(
                "id", UUID.randomUUID().toString(),
                "aggregateId", orderId.toString(),
                "eventType", "OrderCancelled",
                "payload", Map.of(
                    "orderId", orderId.toString(),
                    "paymentId", "not-a-uuid",
                    "totalCents", 5000,
                    "currency", "INR",
                    "reason", "test")));

            ConsumerRecord<String, String> record = new ConsumerRecord<>("orders.events", 0, 0L, null, json);
            assertThatThrownBy(() -> consumer.onOrderEvent(record))
                .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(paymentRepository, paymentService, refundService);
        }

        @Test
        @DisplayName("Raises when payment is missing so the record is not silently lost")
        void skipsWhenPaymentNotFound() throws Exception {
            UUID paymentId = UUID.randomUUID();
            UUID orderId = UUID.randomUUID();

            when(paymentRepository.findById(paymentId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> fireOrderCancelledEvent(orderId, paymentId, 5000, "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Payment not found");

            verify(paymentService, never()).voidAuth(any(), any());
            verify(refundService, never()).refund(any(), any());
        }

        @Test
        @DisplayName("Uses reason default when event reason is null")
        void defaultReasonWhenNull() throws Exception {
            UUID paymentId = UUID.randomUUID();
            UUID orderId = UUID.randomUUID();

            Payment payment = buildPayment(paymentId, orderId, PaymentStatus.CAPTURED, 5000, 5000, 0);
            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
            when(refundService.refund(eq(paymentId), any(RefundRequest.class)))
                .thenReturn(buildRefundResponse());

            String json = objectMapper.writeValueAsString(Map.of(
                "id", UUID.randomUUID().toString(),
                "aggregateId", orderId.toString(),
                "eventType", "OrderCancelled",
                "payload", Map.of(
                    "orderId", orderId.toString(),
                    "paymentId", paymentId.toString(),
                    "totalCents", 5000,
                    "currency", "INR")));

            ConsumerRecord<String, String> record = new ConsumerRecord<>("orders.events", 0, 0L, null, json);
            consumer.onOrderEvent(record);

            ArgumentCaptor<RefundRequest> captor = ArgumentCaptor.forClass(RefundRequest.class);
            verify(refundService).refund(eq(paymentId), captor.capture());
            assertThat(captor.getValue().reason()).isEqualTo("Order cancellation");
        }
    }

    // --- Helpers ---

    private void fireOrderCancelledEvent(UUID orderId, UUID paymentId, long totalCents, String reason) throws Exception {
        String json = objectMapper.writeValueAsString(Map.of(
            "id", UUID.randomUUID().toString(),
            "aggregateId", orderId.toString(),
            "eventType", "OrderCancelled",
            "payload", Map.of(
                "orderId", orderId.toString(),
                "paymentId", paymentId.toString(),
                "totalCents", totalCents,
                "currency", "INR",
                "reason", reason)));

        ConsumerRecord<String, String> record = new ConsumerRecord<>("orders.events", 0, 0L, null, json);
        consumer.onOrderEvent(record);
    }

    private Payment buildPayment(UUID id, UUID orderId, PaymentStatus status,
                                  long amountCents, long capturedCents, long refundedCents) {
        Payment payment = new Payment();
        payment.setId(id);
        payment.setOrderId(orderId);
        payment.setStatus(status);
        payment.setAmountCents(amountCents);
        payment.setCapturedCents(capturedCents);
        payment.setRefundedCents(refundedCents);
        payment.setCurrency("INR");
        payment.setIdempotencyKey(UUID.randomUUID().toString());
        payment.setPspReference("psp_" + UUID.randomUUID());
        return payment;
    }

    private PaymentResponse buildPaymentResponse(UUID id, PaymentStatus status) {
        return new PaymentResponse(id, status.name(), "psp_ref");
    }

    private RefundResponse buildRefundResponse() {
        return new RefundResponse(UUID.randomUUID(), "COMPLETED", 10000);
    }
}
