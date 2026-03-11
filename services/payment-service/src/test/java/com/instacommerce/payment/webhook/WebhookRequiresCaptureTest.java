package com.instacommerce.payment.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.repository.LedgerEntryRepository;
import com.instacommerce.payment.repository.PaymentRepository;
import com.instacommerce.payment.repository.ProcessedWebhookEventRepository;
import com.instacommerce.payment.repository.RefundRepository;
import com.instacommerce.payment.service.AuditLogService;
import com.instacommerce.payment.service.LedgerService;
import com.instacommerce.payment.service.OutboxService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Wave 14 Lane C tests for {@link WebhookEventProcessor}:
 * <ul>
 *   <li>{@code payment_intent.requires_capture} webhook transitions
 *       AUTHORIZE_PENDING → AUTHORIZED with ledger + outbox + audit.</li>
 *   <li>Idempotency for already-AUTHORIZED and terminal states.</li>
 *   <li>Direct capture race condition: {@code payment_intent.succeeded} arriving
 *       while payment is still AUTHORIZE_PENDING transitions directly to CAPTURED
 *       with combined authorization hold + capture ledger entries.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class WebhookRequiresCaptureTest {

    private static final String PSP_REF = "pi_requires_capture_test";
    private static final String EVENT_ID = "evt_rc_001";

    @Mock PaymentRepository paymentRepository;
    @Mock ProcessedWebhookEventRepository processedWebhookEventRepository;
    @Mock RefundRepository refundRepository;
    @Mock LedgerEntryRepository ledgerEntryRepository;
    @Mock LedgerService ledgerService;
    @Mock OutboxService outboxService;
    @Mock AuditLogService auditLogService;
    @Mock EntityManager entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SimpleMeterRegistry meterRegistry;

    private WebhookEventProcessor processorOutboxOn;
    private WebhookEventProcessor processorOutboxOff;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        processorOutboxOn = new WebhookEventProcessor(
            paymentRepository, processedWebhookEventRepository, refundRepository,
            ledgerEntryRepository, ledgerService, outboxService, auditLogService, meterRegistry,
            /* refundOutboxEnabled */ false,
            /* captureVoidOutboxEnabled */ true);
        processorOutboxOff = new WebhookEventProcessor(
            paymentRepository, processedWebhookEventRepository, refundRepository,
            ledgerEntryRepository, ledgerService, outboxService, auditLogService, meterRegistry,
            /* refundOutboxEnabled */ false,
            /* captureVoidOutboxEnabled */ false);
        ReflectionTestUtils.setField(processorOutboxOn, "entityManager", entityManager);
        ReflectionTestUtils.setField(processorOutboxOff, "entityManager", entityManager);
    }

    // ── helpers ──────────────────────────────────────────────────────

    private Payment paymentInStatus(PaymentStatus status, long amountCents) {
        Payment p = new Payment();
        p.setId(UUID.randomUUID());
        p.setOrderId(UUID.randomUUID());
        p.setPspReference(PSP_REF);
        p.setAmountCents(amountCents);
        boolean captured = status == PaymentStatus.CAPTURED
            || status == PaymentStatus.PARTIALLY_REFUNDED
            || status == PaymentStatus.REFUNDED;
        p.setCapturedCents(captured ? amountCents : 0);
        p.setRefundedCents(0);
        p.setCurrency("INR");
        p.setStatus(status);
        p.setIdempotencyKey("key-" + UUID.randomUUID());
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        return p;
    }

    private ObjectNode captureObjectNode(long amountReceived) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", PSP_REF);
        node.put("amount_received", amountReceived);
        return node;
    }

    private ObjectNode emptyObjectNode() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", PSP_REF);
        return node;
    }

    private void stubPaymentLookup(Payment payment) {
        when(paymentRepository.findByPspReferenceForUpdate(PSP_REF))
            .thenReturn(Optional.of(payment));
    }

    private void stubPaymentLookupWithSave(Payment payment) {
        stubPaymentLookup(payment);
        when(paymentRepository.save(any(Payment.class)))
            .thenAnswer(inv -> inv.getArgument(0));
    }

    // ═══════════════════════════════════════════════════════════════
    // 1. requires_capture transitions
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("payment_intent.requires_capture")
    class RequiresCapture {

        @Test
        @DisplayName("AUTHORIZE_PENDING → AUTHORIZED with ledger + audit")
        void authorizePending_transitionsToAuthorized() {
            Payment payment = paymentInStatus(PaymentStatus.AUTHORIZE_PENDING, 5000);
            stubPaymentLookupWithSave(payment);

            processorOutboxOff.processEvent(EVENT_ID, "payment_intent.requires_capture",
                PSP_REF, emptyObjectNode(), null);

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);

            verify(ledgerService).recordDoubleEntry(
                eq(payment.getId()), eq(5000L),
                eq("customer_receivable"), eq("authorization_hold"),
                eq("AUTHORIZATION"), eq(payment.getId().toString()),
                eq("Authorization hold (webhook)"));

            verify(auditLogService).logSafely(isNull(),
                eq("WEBHOOK_AUTHORIZED"),
                eq("Payment"),
                eq(payment.getId().toString()),
                any(Map.class));

            assertThat(meterRegistry.counter("payment.webhook.requires_capture",
                "outcome", "processed").count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("AUTHORIZE_PENDING → AUTHORIZED with outbox when enabled")
        void authorizePending_publishesOutboxWhenEnabled() {
            Payment payment = paymentInStatus(PaymentStatus.AUTHORIZE_PENDING, 7500);
            stubPaymentLookupWithSave(payment);

            processorOutboxOn.processEvent(EVENT_ID, "payment_intent.requires_capture",
                PSP_REF, emptyObjectNode(), null);

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);

            verify(outboxService).publish(
                eq("Payment"),
                eq(payment.getId().toString()),
                eq("PaymentAuthorized"),
                any(Map.class));

            assertThat(meterRegistry.counter("payment.webhook.requires_capture",
                "outcome", "outbox_published").count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("AUTHORIZE_PENDING → AUTHORIZED suppresses outbox when disabled")
        void authorizePending_noOutboxWhenDisabled() {
            Payment payment = paymentInStatus(PaymentStatus.AUTHORIZE_PENDING, 3000);
            stubPaymentLookupWithSave(payment);

            processorOutboxOff.processEvent(EVENT_ID, "payment_intent.requires_capture",
                PSP_REF, emptyObjectNode(), null);

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
            verifyNoInteractions(outboxService);
        }

        @Test
        @DisplayName("Idempotent for already-AUTHORIZED payment")
        void alreadyAuthorized_idempotent() {
            Payment payment = paymentInStatus(PaymentStatus.AUTHORIZED, 5000);
            stubPaymentLookup(payment);

            processorOutboxOff.processEvent(EVENT_ID, "payment_intent.requires_capture",
                PSP_REF, emptyObjectNode(), null);

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
            verify(paymentRepository, never()).save(any());
            verifyNoInteractions(ledgerService);
            verifyNoInteractions(auditLogService);
        }

        @Test
        @DisplayName("Idempotent for already-CAPTURED payment")
        void alreadyCaptured_idempotent() {
            Payment payment = paymentInStatus(PaymentStatus.CAPTURED, 5000);
            stubPaymentLookup(payment);

            processorOutboxOff.processEvent(EVENT_ID, "payment_intent.requires_capture",
                PSP_REF, emptyObjectNode(), null);

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            verify(paymentRepository, never()).save(any());
            verifyNoInteractions(ledgerService);
        }

        @ParameterizedTest
        @EnumSource(value = PaymentStatus.class, names = {"VOIDED", "FAILED", "REFUNDED", "PARTIALLY_REFUNDED"})
        @DisplayName("Idempotent for terminal states")
        void terminalState_idempotent(PaymentStatus terminalStatus) {
            Payment payment = paymentInStatus(terminalStatus, 5000);
            stubPaymentLookup(payment);

            processorOutboxOff.processEvent(EVENT_ID, "payment_intent.requires_capture",
                PSP_REF, emptyObjectNode(), null);

            assertThat(payment.getStatus()).isEqualTo(terminalStatus);
            verify(paymentRepository, never()).save(any());
            verifyNoInteractions(ledgerService);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 2. Direct capture race condition (AUTHORIZE_PENDING + succeeded)
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Direct capture from AUTHORIZE_PENDING (race condition)")
    class DirectCaptureRace {

        @Test
        @DisplayName("AUTHORIZE_PENDING → CAPTURED with auth hold + capture ledger entries")
        void authorizePending_directCapture_fullLedger() {
            Payment payment = paymentInStatus(PaymentStatus.AUTHORIZE_PENDING, 10000);
            stubPaymentLookupWithSave(payment);

            processorOutboxOff.processEvent(EVENT_ID, "payment_intent.succeeded",
                PSP_REF, captureObjectNode(10000), null);

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(payment.getCapturedCents()).isEqualTo(10000);

            // Authorization hold should be recorded first
            verify(ledgerService).recordDoubleEntry(
                eq(payment.getId()), eq(10000L),
                eq("customer_receivable"), eq("authorization_hold"),
                eq("AUTHORIZATION"), eq(payment.getId().toString()),
                eq("Authorization hold (webhook direct capture)"));

            // Then the capture entry
            verify(ledgerService).recordDoubleEntry(
                eq(payment.getId()), eq(10000L),
                eq("authorization_hold"), eq("merchant_payable"),
                eq("CAPTURE"), eq(payment.getId().toString()),
                eq("Capture (webhook)"));

            assertThat(meterRegistry.counter("payment.webhook.capture",
                "outcome", "direct_from_authorize_pending").count()).isEqualTo(1.0);
            assertThat(meterRegistry.counter("payment.webhook.capture",
                "outcome", "processed").count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Direct capture publishes PaymentAuthorized + PaymentCaptured outbox when enabled")
        void authorizePending_directCapture_outboxEnabled() {
            Payment payment = paymentInStatus(PaymentStatus.AUTHORIZE_PENDING, 8000);
            stubPaymentLookupWithSave(payment);

            processorOutboxOn.processEvent(EVENT_ID, "payment_intent.succeeded",
                PSP_REF, captureObjectNode(8000), null);

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);

            // Both PaymentAuthorized and PaymentCaptured outbox events
            verify(outboxService).publish(
                eq("Payment"),
                eq(payment.getId().toString()),
                eq("PaymentAuthorized"),
                any(Map.class));
            verify(outboxService).publish(
                eq("Payment"),
                eq(payment.getId().toString()),
                eq("PaymentCaptured"),
                any(Map.class));
        }

        @Test
        @DisplayName("Direct capture suppresses outbox when disabled")
        void authorizePending_directCapture_outboxDisabled() {
            Payment payment = paymentInStatus(PaymentStatus.AUTHORIZE_PENDING, 6000);
            stubPaymentLookupWithSave(payment);

            processorOutboxOff.processEvent(EVENT_ID, "payment_intent.succeeded",
                PSP_REF, captureObjectNode(6000), null);

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            verifyNoInteractions(outboxService);
        }

        @Test
        @DisplayName("Direct partial capture records auth hold + capture + remainder release")
        void authorizePending_partialCapture_releasesRemainder() {
            Payment payment = paymentInStatus(PaymentStatus.AUTHORIZE_PENDING, 10000);
            stubPaymentLookupWithSave(payment);

            processorOutboxOff.processEvent(EVENT_ID, "payment_intent.succeeded",
                PSP_REF, captureObjectNode(7000), null);

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(payment.getCapturedCents()).isEqualTo(7000);

            // Authorization hold
            verify(ledgerService).recordDoubleEntry(
                eq(payment.getId()), eq(10000L),
                eq("customer_receivable"), eq("authorization_hold"),
                eq("AUTHORIZATION"), eq(payment.getId().toString()),
                eq("Authorization hold (webhook direct capture)"));

            // Capture
            verify(ledgerService).recordDoubleEntry(
                eq(payment.getId()), eq(7000L),
                eq("authorization_hold"), eq("merchant_payable"),
                eq("CAPTURE"), eq(payment.getId().toString()),
                eq("Capture (webhook)"));

            // Remainder release (10000 - 7000 = 3000)
            verify(ledgerService).recordDoubleEntry(
                eq(payment.getId()), eq(3000L),
                eq("authorization_hold"), eq("customer_receivable"),
                eq("PARTIAL_CAPTURE_RELEASE"), eq(payment.getId().toString()),
                eq("Partial capture remainder release (webhook)"));
        }
    }
}
