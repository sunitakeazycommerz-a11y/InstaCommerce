package com.instacommerce.payment.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.domain.model.ProcessedWebhookEvent;
import com.instacommerce.payment.repository.PaymentRepository;
import com.instacommerce.payment.repository.ProcessedWebhookEventRepository;
import com.instacommerce.payment.service.LedgerService;
import com.instacommerce.payment.service.OutboxService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WebhookRefundHandlerTest {

    private static final String PSP_REF = "pi_test_refund_123";

    @Mock PaymentRepository paymentRepository;
    @Mock ProcessedWebhookEventRepository processedWebhookEventRepository;
    @Mock LedgerService ledgerService;
    @Mock OutboxService outboxService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SimpleMeterRegistry meterRegistry;

    private WebhookEventProcessor processorOutboxEnabled;
    private WebhookEventProcessor processorOutboxDisabled;
    private WebhookEventHandler handlerOutboxEnabled;
    private WebhookEventHandler handlerOutboxDisabled;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        processorOutboxEnabled = new WebhookEventProcessor(
            paymentRepository, processedWebhookEventRepository,
            ledgerService, outboxService, meterRegistry, true);
        processorOutboxDisabled = new WebhookEventProcessor(
            paymentRepository, processedWebhookEventRepository,
            ledgerService, outboxService, meterRegistry, false);
        handlerOutboxEnabled = new WebhookEventHandler(
            objectMapper, processedWebhookEventRepository, processorOutboxEnabled);
        handlerOutboxDisabled = new WebhookEventHandler(
            objectMapper, processedWebhookEventRepository, processorOutboxDisabled);
    }

    // --- Helpers ---

    private Payment payment(PaymentStatus status, long capturedCents, long refundedCents) {
        Payment p = new Payment();
        p.setId(UUID.randomUUID());
        p.setOrderId(UUID.randomUUID());
        p.setAmountCents(10000);
        p.setCapturedCents(capturedCents);
        p.setRefundedCents(refundedCents);
        p.setCurrency("INR");
        p.setStatus(status);
        p.setPspReference(PSP_REF);
        p.setIdempotencyKey("key-" + UUID.randomUUID());
        p.setCreatedAt(Instant.now().minusSeconds(3600));
        p.setUpdatedAt(Instant.now().minusSeconds(3600));
        return p;
    }

    private String refundWebhookPayload(String eventId, long amountRefunded) {
        return """
            {
              "id": "%s",
              "type": "charge.refunded",
              "data": {
                "object": {
                  "payment_intent": "%s",
                  "amount_refunded": %d
                }
              }
            }
            """.formatted(eventId, PSP_REF, amountRefunded);
    }

    private void stubPaymentLookup(Payment payment) {
        when(paymentRepository.findByPspReference(PSP_REF)).thenReturn(Optional.of(payment));
    }

    private void stubPaymentLookupWithSave(Payment payment) {
        stubPaymentLookup(payment);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // --- State guard tests ---

    @Nested
    @DisplayName("State guard: refund only from CAPTURED or PARTIALLY_REFUNDED")
    class StateGuard {

        @Test
        @DisplayName("CAPTURED payment accepts refund webhook")
        void capturedPayment_acceptsRefund() {
            Payment p = payment(PaymentStatus.CAPTURED, 10000, 0);
            stubPaymentLookupWithSave(p);

            processorOutboxDisabled.processEvent("evt_1", "charge.refunded", PSP_REF,
                objectMapper.createObjectNode().put("amount_refunded", 3000));

            assertThat(p.getRefundedCents()).isEqualTo(3000);
            assertThat(p.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
            verify(ledgerService).recordDoubleEntry(
                eq(p.getId()), eq(3000L),
                eq("merchant_payable"), eq("customer_receivable"),
                eq("REFUND"), anyString(), eq("Refund (webhook)"));
        }

        @Test
        @DisplayName("PARTIALLY_REFUNDED payment accepts refund webhook")
        void partiallyRefundedPayment_acceptsRefund() {
            Payment p = payment(PaymentStatus.PARTIALLY_REFUNDED, 10000, 3000);
            stubPaymentLookupWithSave(p);

            processorOutboxDisabled.processEvent("evt_2", "charge.refunded", PSP_REF,
                objectMapper.createObjectNode().put("amount_refunded", 10000));

            assertThat(p.getRefundedCents()).isEqualTo(10000);
            assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
            verify(ledgerService).recordDoubleEntry(
                eq(p.getId()), eq(7000L),
                eq("merchant_payable"), eq("customer_receivable"),
                eq("REFUND"), anyString(), eq("Refund (webhook)"));
        }

        @ParameterizedTest(name = "{0} is terminally rejected")
        @EnumSource(value = PaymentStatus.class, names = {"VOIDED", "FAILED"})
        @DisplayName("Terminal non-refundable states are permanently rejected without side effects")
        void terminalNonRefundableState_rejected(PaymentStatus terminalStatus) {
            Payment p = payment(terminalStatus, 0, 0);
            stubPaymentLookup(p);

            processorOutboxDisabled.processEvent("evt_bad", "charge.refunded", PSP_REF,
                objectMapper.createObjectNode().put("amount_refunded", 5000));

            assertThat(p.getRefundedCents()).isEqualTo(0);
            assertThat(p.getStatus()).isEqualTo(terminalStatus);
            verify(paymentRepository, never()).save(any());
            verifyNoInteractions(ledgerService);
            verifyNoInteractions(outboxService);
            assertThat(meterRegistry.counter("payment.webhook.refund", "outcome", "terminal_rejected").count())
                .isEqualTo(1.0);
        }

        @ParameterizedTest(name = "{0} defers for retry")
        @EnumSource(value = PaymentStatus.class, names = {
            "AUTHORIZE_PENDING", "AUTHORIZED", "CAPTURE_PENDING", "VOID_PENDING"
        })
        @DisplayName("Transient non-refundable states throw to allow Stripe retry")
        void transientNonRefundableState_defersForRetry(PaymentStatus transientStatus) {
            Payment p = payment(transientStatus, 0, 0);
            stubPaymentLookup(p);

            assertThatThrownBy(() ->
                processorOutboxDisabled.processEvent("evt_transient", "charge.refunded", PSP_REF,
                    objectMapper.createObjectNode().put("amount_refunded", 5000)))
                .isInstanceOf(WebhookEventHandler.TransientWebhookStateException.class);

            assertThat(p.getRefundedCents()).isEqualTo(0);
            assertThat(p.getStatus()).isEqualTo(transientStatus);
            verify(paymentRepository, never()).save(any());
            verifyNoInteractions(ledgerService);
            verifyNoInteractions(outboxService);
            assertThat(meterRegistry.counter("payment.webhook.refund", "outcome", "transient_deferred").count())
                .isEqualTo(1.0);
        }

        @Test
        @DisplayName("REFUNDED state is idempotent (delta=0, no side effects)")
        void refundedState_idempotent() {
            Payment p = payment(PaymentStatus.REFUNDED, 10000, 10000);
            stubPaymentLookup(p);

            processorOutboxDisabled.processEvent("evt_term", "charge.refunded", PSP_REF,
                objectMapper.createObjectNode().put("amount_refunded", 10000));

            verify(paymentRepository, never()).save(any());
            verifyNoInteractions(ledgerService);
        }
    }

    // --- Outbox emission tests ---

    @Nested
    @DisplayName("Outbox: PaymentRefunded publish gated by flag")
    class OutboxEmission {

        @Test
        @DisplayName("Outbox publishes PaymentRefunded when flag enabled")
        void outboxEnabled_publishesEvent() {
            Payment p = payment(PaymentStatus.CAPTURED, 10000, 0);
            stubPaymentLookupWithSave(p);

            processorOutboxEnabled.processEvent("evt_out_1", "charge.refunded", PSP_REF,
                objectMapper.createObjectNode().put("amount_refunded", 5000));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<java.util.Map<String, Object>> payloadCaptor =
                ArgumentCaptor.forClass(java.util.Map.class);
            verify(outboxService).publish(
                eq("Payment"), eq(p.getId().toString()),
                eq("PaymentRefunded"), payloadCaptor.capture());

            var payload = payloadCaptor.getValue();
            assertThat(payload.get("refundId")).isNotNull();
            assertThat(payload.get("refundId").toString()).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
            assertThat(payload.get("orderId")).isEqualTo(p.getOrderId());
            assertThat(payload.get("paymentId")).isEqualTo(p.getId());
            assertThat(payload.get("amountCents")).isEqualTo(5000L);
            assertThat(payload.get("currency")).isEqualTo("INR");
            assertThat(payload).containsKey("refundedAt");
            assertThat(payload.get("reason")).isEqualTo("webhook");
        }

        @Test
        @DisplayName("Outbox does NOT publish when flag disabled")
        void outboxDisabled_noPublish() {
            Payment p = payment(PaymentStatus.CAPTURED, 10000, 0);
            stubPaymentLookupWithSave(p);

            processorOutboxDisabled.processEvent("evt_out_2", "charge.refunded", PSP_REF,
                objectMapper.createObjectNode().put("amount_refunded", 5000));

            verify(ledgerService).recordDoubleEntry(
                any(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString());
            verifyNoInteractions(outboxService);
        }

        @Test
        @DisplayName("Outbox not published when delta is zero (idempotent replay)")
        void zeroDelta_noOutbox() {
            Payment p = payment(PaymentStatus.CAPTURED, 10000, 5000);
            stubPaymentLookup(p);

            processorOutboxEnabled.processEvent("evt_out_3", "charge.refunded", PSP_REF,
                objectMapper.createObjectNode().put("amount_refunded", 5000));

            verify(paymentRepository, never()).save(any());
            verifyNoInteractions(outboxService);
            verifyNoInteractions(ledgerService);
        }
    }

    // --- Dedup tests ---

    @Nested
    @DisplayName("Dedup: duplicate webhook events")
    class Deduplication {

        @Test
        @DisplayName("Duplicate webhook via existsById is skipped before processing")
        void duplicateWebhook_skippedByExistsCheck() {
            when(processedWebhookEventRepository.existsById("evt_dup")).thenReturn(true);

            handlerOutboxEnabled.handle(refundWebhookPayload("evt_dup", 5000));

            verifyNoInteractions(paymentRepository);
            verifyNoInteractions(ledgerService);
            verifyNoInteractions(outboxService);
        }

        @Test
        @DisplayName("First webhook processes, replay with same amount_refunded produces zero delta")
        void replayWebhook_zeroDelta() {
            Payment p = payment(PaymentStatus.CAPTURED, 10000, 0);
            stubPaymentLookupWithSave(p);

            // First call: processes normally
            processorOutboxEnabled.processEvent("evt_first", "charge.refunded", PSP_REF,
                objectMapper.createObjectNode().put("amount_refunded", 5000));

            assertThat(p.getRefundedCents()).isEqualTo(5000);
            verify(ledgerService).recordDoubleEntry(
                any(), eq(5000L), anyString(), anyString(), anyString(), anyString(), anyString());
            verify(outboxService).publish(
                anyString(), anyString(), eq("PaymentRefunded"), any());

            // Simulate second webhook with same cumulative amount_refunded
            // (payment.refundedCents is now 5000 from first call)
            processorOutboxEnabled.processEvent("evt_second", "charge.refunded", PSP_REF,
                objectMapper.createObjectNode().put("amount_refunded", 5000));

            // No additional ledger or outbox calls beyond the first
            verifyNoMoreInteractions(ledgerService);
            verifyNoMoreInteractions(outboxService);
        }
    }

    // --- Full handle() integration test ---

    @Nested
    @DisplayName("Full handle() path")
    class FullHandlePath {

        @Test
        @DisplayName("Valid captured refund through handle() end-to-end")
        void validCapturedRefund_endToEnd() {
            Payment p = payment(PaymentStatus.CAPTURED, 10000, 0);
            when(processedWebhookEventRepository.existsById("evt_e2e")).thenReturn(false);
            when(paymentRepository.findByPspReference(PSP_REF)).thenReturn(Optional.of(p));
            when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

            handlerOutboxEnabled.handle(refundWebhookPayload("evt_e2e", 10000));

            assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(p.getRefundedCents()).isEqualTo(10000);
            verify(processedWebhookEventRepository).saveAndFlush(any());
            verify(ledgerService).recordDoubleEntry(
                eq(p.getId()), eq(10000L),
                eq("merchant_payable"), eq("customer_receivable"),
                eq("REFUND"), anyString(), eq("Refund (webhook)"));
            verify(outboxService).publish(
                eq("Payment"), eq(p.getId().toString()),
                eq("PaymentRefunded"), any());
        }

        @Test
        @DisplayName("Transient state through handle() propagates to controller as 500")
        void transientState_propagatesToController() {
            Payment p = payment(PaymentStatus.CAPTURE_PENDING, 0, 0);
            when(processedWebhookEventRepository.existsById("evt_trans")).thenReturn(false);
            when(paymentRepository.findByPspReference(PSP_REF)).thenReturn(Optional.of(p));

            assertThatThrownBy(() ->
                handlerOutboxEnabled.handle(refundWebhookPayload("evt_trans", 5000)))
                .isInstanceOf(WebhookEventHandler.TransientWebhookStateException.class);
        }
    }

    // --- Contract compliance tests ---

    @Nested
    @DisplayName("Contract: PaymentRefunded payload")
    class ContractCompliance {

        @Test
        @DisplayName("syntheticRefundId is deterministic for the same eventId")
        void syntheticRefundId_deterministic() {
            UUID first = WebhookEventProcessor.syntheticRefundId("evt_abc");
            UUID second = WebhookEventProcessor.syntheticRefundId("evt_abc");
            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("syntheticRefundId differs for different eventIds")
        void syntheticRefundId_distinct() {
            UUID a = WebhookEventProcessor.syntheticRefundId("evt_1");
            UUID b = WebhookEventProcessor.syntheticRefundId("evt_2");
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Outbox payload contains all required PaymentRefunded contract fields")
        void outboxPayload_contractSafe() {
            Payment p = payment(PaymentStatus.CAPTURED, 10000, 0);
            stubPaymentLookupWithSave(p);

            processorOutboxEnabled.processEvent("evt_contract", "charge.refunded", PSP_REF,
                objectMapper.createObjectNode().put("amount_refunded", 4000));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<java.util.Map<String, Object>> payloadCaptor =
                ArgumentCaptor.forClass(java.util.Map.class);
            verify(outboxService).publish(
                eq("Payment"), eq(p.getId().toString()),
                eq("PaymentRefunded"), payloadCaptor.capture());

            var payload = payloadCaptor.getValue();
            // All required fields per PaymentRefunded.v1.json
            assertThat(payload).containsKeys("refundId", "paymentId", "orderId", "amountCents", "currency");
            assertThat(payload.get("refundId")).isEqualTo(
                WebhookEventProcessor.syntheticRefundId("evt_contract").toString());
        }
    }

    // --- Transaction boundary delegation tests ---

    @Nested
    @DisplayName("Transaction boundary: handle() delegates to processor bean")
    class TransactionBoundary {

        @Test
        @DisplayName("handle() calls processor.processEvent(), not self-invocation")
        void handle_delegatesToProcessorBean() {
            WebhookEventProcessor mockProcessor = mock(WebhookEventProcessor.class);
            WebhookEventHandler handler = new WebhookEventHandler(
                objectMapper, processedWebhookEventRepository, mockProcessor);

            when(processedWebhookEventRepository.existsById("evt_delegate")).thenReturn(false);

            handler.handle(refundWebhookPayload("evt_delegate", 5000));

            verify(mockProcessor).processEvent(
                eq("evt_delegate"), eq("charge.refunded"), eq(PSP_REF), any(JsonNode.class));
        }

        @Test
        @DisplayName("Transient deferral propagates through handle() — dedup rollback depends on TX boundary")
        void transientDeferral_exceptionPropagatesForRollback() {
            // Verifies the structural precondition for rollback: the exception thrown by the
            // processor is NOT swallowed by the handler's retry loop (it only retries on
            // ObjectOptimisticLockingFailureException). In a Spring context, the @Transactional
            // proxy on the processor rolls back the dedup INSERT when this RuntimeException
            // propagates. A full Testcontainers integration test would verify the actual
            // rollback; this unit test confirms the exception path is intact.
            Payment p = payment(PaymentStatus.CAPTURE_PENDING, 0, 0);
            when(processedWebhookEventRepository.existsById("evt_rollback")).thenReturn(false);
            when(paymentRepository.findByPspReference(PSP_REF)).thenReturn(Optional.of(p));

            assertThatThrownBy(() ->
                handlerOutboxEnabled.handle(refundWebhookPayload("evt_rollback", 5000)))
                .isInstanceOf(WebhookEventHandler.TransientWebhookStateException.class);

            // Dedup save was attempted inside processEvent; at runtime the transaction rollback
            // undoes it. Verify the exception was not caught by the retry loop.
            verify(processedWebhookEventRepository).saveAndFlush(any(ProcessedWebhookEvent.class));
            verify(paymentRepository, never()).save(any());
        }

        @Test
        @DisplayName("Terminal rejection through handle() does NOT throw — dedup marker persists correctly")
        void terminalRejection_doesNotThrow() {
            Payment p = payment(PaymentStatus.VOIDED, 0, 0);
            when(processedWebhookEventRepository.existsById("evt_terminal")).thenReturn(false);
            when(paymentRepository.findByPspReference(PSP_REF)).thenReturn(Optional.of(p));

            // Should complete without exception; dedup marker commits (correct behavior)
            handlerOutboxEnabled.handle(refundWebhookPayload("evt_terminal", 5000));

            verify(processedWebhookEventRepository).saveAndFlush(any(ProcessedWebhookEvent.class));
            verify(paymentRepository, never()).save(any());
            assertThat(meterRegistry.counter("payment.webhook.refund", "outcome", "terminal_rejected").count())
                .isEqualTo(1.0);
        }
    }
}
