package com.instacommerce.payment.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.repository.PaymentRepository;
import com.instacommerce.payment.repository.ProcessedWebhookEventRepository;
import com.instacommerce.payment.service.LedgerService;
import com.instacommerce.payment.service.OutboxService;
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

    private WebhookEventHandler handlerOutboxEnabled;
    private WebhookEventHandler handlerOutboxDisabled;

    @BeforeEach
    void setUp() {
        handlerOutboxEnabled = new WebhookEventHandler(
            objectMapper, paymentRepository, processedWebhookEventRepository,
            ledgerService, outboxService, true);
        handlerOutboxDisabled = new WebhookEventHandler(
            objectMapper, paymentRepository, processedWebhookEventRepository,
            ledgerService, outboxService, false);
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

            handlerOutboxDisabled.processEvent("evt_1", "charge.refunded", PSP_REF,
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

            handlerOutboxDisabled.processEvent("evt_2", "charge.refunded", PSP_REF,
                objectMapper.createObjectNode().put("amount_refunded", 10000));

            assertThat(p.getRefundedCents()).isEqualTo(10000);
            assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
            verify(ledgerService).recordDoubleEntry(
                eq(p.getId()), eq(7000L),
                eq("merchant_payable"), eq("customer_receivable"),
                eq("REFUND"), anyString(), eq("Refund (webhook)"));
        }

        @ParameterizedTest(name = "{0} is rejected")
        @EnumSource(value = PaymentStatus.class, names = {
            "AUTHORIZE_PENDING", "AUTHORIZED", "CAPTURE_PENDING",
            "VOID_PENDING", "VOIDED", "FAILED"
        })
        @DisplayName("Non-refundable states are rejected without side effects")
        void nonRefundableState_rejected(PaymentStatus invalidStatus) {
            Payment p = payment(invalidStatus, 0, 0);
            stubPaymentLookup(p);

            handlerOutboxDisabled.processEvent("evt_bad", "charge.refunded", PSP_REF,
                objectMapper.createObjectNode().put("amount_refunded", 5000));

            assertThat(p.getRefundedCents()).isEqualTo(0);
            assertThat(p.getStatus()).isEqualTo(invalidStatus);
            verify(paymentRepository, never()).save(any());
            verifyNoInteractions(ledgerService);
            verifyNoInteractions(outboxService);
        }

        @Test
        @DisplayName("REFUNDED state is not in REFUNDABLE_STATES (already terminal)")
        void refundedState_rejected() {
            Payment p = payment(PaymentStatus.REFUNDED, 10000, 10000);
            stubPaymentLookup(p);

            handlerOutboxDisabled.processEvent("evt_term", "charge.refunded", PSP_REF,
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

            handlerOutboxEnabled.processEvent("evt_out_1", "charge.refunded", PSP_REF,
                objectMapper.createObjectNode().put("amount_refunded", 5000));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<java.util.Map<String, Object>> payloadCaptor =
                ArgumentCaptor.forClass(java.util.Map.class);
            verify(outboxService).publish(
                eq("Payment"), eq(p.getId().toString()),
                eq("PaymentRefunded"), payloadCaptor.capture());

            var payload = payloadCaptor.getValue();
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

            handlerOutboxDisabled.processEvent("evt_out_2", "charge.refunded", PSP_REF,
                objectMapper.createObjectNode().put("amount_refunded", 5000));

            verify(ledgerService).recordDoubleEntry(
                any(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString());
            verifyNoInteractions(outboxService);
        }

        @Test
        @DisplayName("Outbox not published when delta is zero (idempotent replay)")
        void zeroDelta_noOutbox() {
            Payment p = payment(PaymentStatus.CAPTURED, 10000, 5000);
            stubPaymentLookupWithSave(p);

            handlerOutboxEnabled.processEvent("evt_out_3", "charge.refunded", PSP_REF,
                objectMapper.createObjectNode().put("amount_refunded", 5000));

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
            handlerOutboxEnabled.processEvent("evt_first", "charge.refunded", PSP_REF,
                objectMapper.createObjectNode().put("amount_refunded", 5000));

            assertThat(p.getRefundedCents()).isEqualTo(5000);
            verify(ledgerService).recordDoubleEntry(
                any(), eq(5000L), anyString(), anyString(), anyString(), anyString(), anyString());
            verify(outboxService).publish(
                anyString(), anyString(), eq("PaymentRefunded"), any());

            // Simulate second webhook with same cumulative amount_refunded
            // (payment.refundedCents is now 5000 from first call)
            handlerOutboxEnabled.processEvent("evt_second", "charge.refunded", PSP_REF,
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
            verify(processedWebhookEventRepository).save(any());
            verify(ledgerService).recordDoubleEntry(
                eq(p.getId()), eq(10000L),
                eq("merchant_payable"), eq("customer_receivable"),
                eq("REFUND"), anyString(), eq("Refund (webhook)"));
            verify(outboxService).publish(
                eq("Payment"), eq(p.getId().toString()),
                eq("PaymentRefunded"), any());
        }
    }
}
