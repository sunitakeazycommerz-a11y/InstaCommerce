package com.instacommerce.payment.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.repository.PaymentRepository;
import com.instacommerce.payment.repository.ProcessedWebhookEventRepository;
import com.instacommerce.payment.repository.RefundRepository;
import com.instacommerce.payment.service.LedgerService;
import com.instacommerce.payment.service.OutboxService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Wave 10 webhook hardening tests for {@link WebhookEventProcessor}:
 * <ul>
 *   <li>State guards — capture, void, and failed webhooks reject terminal
 *       states and defer transient non-terminal states.</li>
 *   <li>Outbox gating — capture/void outbox emission controlled by
 *       {@code captureVoidOutboxEnabled} flag.</li>
 *   <li>ReferenceId normalization — capture and void ledger entries use
 *       {@code payment.getId().toString()} instead of the Stripe event id.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class WebhookEventProcessorHardeningTest {

    private static final String PSP_REF = "pi_hardening_test";
    private static final String EVENT_ID = "evt_hardening_001";

    @Mock PaymentRepository paymentRepository;
    @Mock ProcessedWebhookEventRepository processedWebhookEventRepository;
    @Mock RefundRepository refundRepository;
    @Mock LedgerService ledgerService;
    @Mock OutboxService outboxService;
    @Mock EntityManager entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SimpleMeterRegistry meterRegistry;

    private WebhookEventProcessor processorCaptureVoidOutboxOn;
    private WebhookEventProcessor processorCaptureVoidOutboxOff;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        processorCaptureVoidOutboxOn = new WebhookEventProcessor(
            paymentRepository, processedWebhookEventRepository, refundRepository,
            ledgerService, outboxService, meterRegistry,
            /* refundOutboxEnabled */ false,
            /* captureVoidOutboxEnabled */ true);
        processorCaptureVoidOutboxOff = new WebhookEventProcessor(
            paymentRepository, processedWebhookEventRepository, refundRepository,
            ledgerService, outboxService, meterRegistry,
            /* refundOutboxEnabled */ false,
            /* captureVoidOutboxEnabled */ false);
        ReflectionTestUtils.setField(processorCaptureVoidOutboxOn, "entityManager", entityManager);
        ReflectionTestUtils.setField(processorCaptureVoidOutboxOff, "entityManager", entityManager);
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
    // 1. State guards
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Capture state guards")
    class CaptureStateGuards {

        @ParameterizedTest(name = "capture rejected when payment in {0}")
        @EnumSource(value = PaymentStatus.class,
            names = {"VOIDED", "FAILED", "REFUNDED", "PARTIALLY_REFUNDED"})
        @DisplayName("capture terminal rejection")
        void captureRejectedForTerminalStates(PaymentStatus status) {
            Payment p = paymentInStatus(status, 5000);
            stubPaymentLookup(p);

            processorCaptureVoidOutboxOff.processEvent(
                EVENT_ID, "payment_intent.succeeded", PSP_REF,
                captureObjectNode(5000), null);

            assertThat(p.getStatus()).isEqualTo(status);
            verify(paymentRepository, never()).save(any());
            verifyNoInteractions(ledgerService, outboxService);
            assertThat(meterRegistry.counter(
                "payment.webhook.capture", "outcome", "terminal_rejected").count())
                .isEqualTo(1.0);
        }

        @ParameterizedTest(name = "capture transient deferral when payment in {0}")
        @EnumSource(value = PaymentStatus.class,
            names = {"AUTHORIZE_PENDING", "VOID_PENDING"})
        @DisplayName("capture transient deferral throws TransientWebhookStateException")
        void captureTransientDeferral(PaymentStatus status) {
            Payment p = paymentInStatus(status, 5000);
            stubPaymentLookup(p);

            assertThatThrownBy(() ->
                processorCaptureVoidOutboxOff.processEvent(
                    EVENT_ID, "payment_intent.succeeded", PSP_REF,
                    captureObjectNode(5000), null))
                .isInstanceOf(WebhookEventHandler.TransientWebhookStateException.class);

            verify(paymentRepository, never()).save(any());
            verifyNoInteractions(ledgerService, outboxService);
        }
    }

    @Nested
    @DisplayName("Void state guards")
    class VoidStateGuards {

        @ParameterizedTest(name = "void rejected when payment in {0}")
        @EnumSource(value = PaymentStatus.class,
            names = {"CAPTURED", "FAILED", "REFUNDED", "PARTIALLY_REFUNDED"})
        @DisplayName("void terminal rejection")
        void voidRejectedForTerminalStates(PaymentStatus status) {
            Payment p = paymentInStatus(status, 5000);
            stubPaymentLookup(p);

            processorCaptureVoidOutboxOff.processEvent(
                EVENT_ID, "payment_intent.canceled", PSP_REF,
                emptyObjectNode(), null);

            assertThat(p.getStatus()).isEqualTo(status);
            verify(paymentRepository, never()).save(any());
            verifyNoInteractions(ledgerService, outboxService);
            assertThat(meterRegistry.counter(
                "payment.webhook.void", "outcome", "terminal_rejected").count())
                .isEqualTo(1.0);
        }

        @ParameterizedTest(name = "void transient deferral when payment in {0}")
        @EnumSource(value = PaymentStatus.class,
            names = {"AUTHORIZE_PENDING", "CAPTURE_PENDING"})
        @DisplayName("void transient deferral throws TransientWebhookStateException")
        void voidTransientDeferral(PaymentStatus status) {
            Payment p = paymentInStatus(status, 5000);
            stubPaymentLookup(p);

            assertThatThrownBy(() ->
                processorCaptureVoidOutboxOff.processEvent(
                    EVENT_ID, "payment_intent.canceled", PSP_REF,
                    emptyObjectNode(), null))
                .isInstanceOf(WebhookEventHandler.TransientWebhookStateException.class);

            verify(paymentRepository, never()).save(any());
            verifyNoInteractions(ledgerService, outboxService);
        }
    }

    @Nested
    @DisplayName("Failed state guards")
    class FailedStateGuards {

        @ParameterizedTest(name = "failed rejected when payment in {0}")
        @EnumSource(value = PaymentStatus.class,
            names = {"CAPTURED", "VOIDED", "REFUNDED", "PARTIALLY_REFUNDED"})
        @DisplayName("failed terminal rejection")
        void failedRejectedForTerminalStates(PaymentStatus status) {
            Payment p = paymentInStatus(status, 5000);
            stubPaymentLookup(p);

            processorCaptureVoidOutboxOff.processEvent(
                EVENT_ID, "payment_intent.payment_failed", PSP_REF,
                emptyObjectNode(), null);

            assertThat(p.getStatus()).isEqualTo(status);
            verify(paymentRepository, never()).save(any());
            verifyNoInteractions(ledgerService);
            assertThat(meterRegistry.counter(
                "payment.webhook.fail", "outcome", "terminal_rejected").count())
                .isEqualTo(1.0);
        }

        @ParameterizedTest(name = "failed processes when payment in {0}")
        @EnumSource(value = PaymentStatus.class,
            names = {"AUTHORIZE_PENDING", "AUTHORIZED", "CAPTURE_PENDING", "VOID_PENDING"})
        @DisplayName("failed marks eligible states as FAILED")
        void failedProcessesEligibleStates(PaymentStatus status) {
            Payment p = paymentInStatus(status, 5000);
            stubPaymentLookupWithSave(p);

            processorCaptureVoidOutboxOff.processEvent(
                EVENT_ID, "payment_intent.payment_failed", PSP_REF,
                emptyObjectNode(), null);

            assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
            verify(paymentRepository).save(p);
            verifyNoInteractions(ledgerService);
            assertThat(meterRegistry.counter(
                "payment.webhook.fail", "outcome", "processed").count())
                .isEqualTo(1.0);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 2. Outbox gating
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Capture/void outbox gating")
    class OutboxGating {

        @Test
        @DisplayName("capture publishes PaymentCaptured when captureVoidOutboxEnabled=true")
        void capturePublishesOutboxWhenEnabled() {
            Payment p = paymentInStatus(PaymentStatus.AUTHORIZED, 5000);
            stubPaymentLookupWithSave(p);

            processorCaptureVoidOutboxOn.processEvent(
                EVENT_ID, "payment_intent.succeeded", PSP_REF,
                captureObjectNode(5000), null);

            assertThat(p.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            verify(outboxService).publish(
                eq("Payment"), eq(p.getId().toString()),
                eq("PaymentCaptured"), any());
        }

        @Test
        @DisplayName("capture does NOT publish outbox when captureVoidOutboxEnabled=false")
        void captureNoOutboxWhenDisabled() {
            Payment p = paymentInStatus(PaymentStatus.AUTHORIZED, 5000);
            stubPaymentLookupWithSave(p);

            processorCaptureVoidOutboxOff.processEvent(
                EVENT_ID, "payment_intent.succeeded", PSP_REF,
                captureObjectNode(5000), null);

            assertThat(p.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            verifyNoInteractions(outboxService);
        }

        @Test
        @DisplayName("void publishes PaymentVoided when captureVoidOutboxEnabled=true")
        void voidPublishesOutboxWhenEnabled() {
            Payment p = paymentInStatus(PaymentStatus.AUTHORIZED, 5000);
            stubPaymentLookupWithSave(p);

            processorCaptureVoidOutboxOn.processEvent(
                EVENT_ID, "payment_intent.canceled", PSP_REF,
                emptyObjectNode(), null);

            assertThat(p.getStatus()).isEqualTo(PaymentStatus.VOIDED);
            verify(outboxService).publish(
                eq("Payment"), eq(p.getId().toString()),
                eq("PaymentVoided"), any());
        }

        @Test
        @DisplayName("void does NOT publish outbox when captureVoidOutboxEnabled=false")
        void voidNoOutboxWhenDisabled() {
            Payment p = paymentInStatus(PaymentStatus.AUTHORIZED, 5000);
            stubPaymentLookupWithSave(p);

            processorCaptureVoidOutboxOff.processEvent(
                EVENT_ID, "payment_intent.canceled", PSP_REF,
                emptyObjectNode(), null);

            assertThat(p.getStatus()).isEqualTo(PaymentStatus.VOIDED);
            verifyNoInteractions(outboxService);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 3. ReferenceId normalization
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ReferenceId normalization: capture/void ledger uses payment UUID")
    class ReferenceIdNormalization {

        @Test
        @DisplayName("capture ledger referenceId is payment.getId(), not Stripe eventId")
        void captureLedgerUsesPaymentId() {
            Payment p = paymentInStatus(PaymentStatus.AUTHORIZED, 5000);
            stubPaymentLookupWithSave(p);

            processorCaptureVoidOutboxOff.processEvent(
                EVENT_ID, "payment_intent.succeeded", PSP_REF,
                captureObjectNode(5000), null);

            verify(ledgerService).recordDoubleEntry(
                eq(p.getId()), eq(5000L),
                eq("authorization_hold"), eq("merchant_payable"),
                eq("CAPTURE"), eq(p.getId().toString()),
                eq("Capture (webhook)"));
        }

        @Test
        @DisplayName("void ledger referenceId is payment.getId(), not Stripe eventId")
        void voidLedgerUsesPaymentId() {
            Payment p = paymentInStatus(PaymentStatus.AUTHORIZED, 5000);
            stubPaymentLookupWithSave(p);

            processorCaptureVoidOutboxOff.processEvent(
                EVENT_ID, "payment_intent.canceled", PSP_REF,
                emptyObjectNode(), null);

            verify(ledgerService).recordDoubleEntry(
                eq(p.getId()), eq(5000L),
                eq("authorization_hold"), eq("customer_receivable"),
                eq("VOID"), eq(p.getId().toString()),
                eq("Authorization void (webhook)"));
        }

        @Test
        @DisplayName("capture with null eventId still uses payment.getId() as referenceId")
        void captureNullEventIdUsesPaymentId() {
            Payment p = paymentInStatus(PaymentStatus.AUTHORIZED, 5000);
            stubPaymentLookupWithSave(p);

            processorCaptureVoidOutboxOff.processEvent(
                null, "payment_intent.succeeded", PSP_REF,
                captureObjectNode(5000), null);

            verify(ledgerService).recordDoubleEntry(
                eq(p.getId()), eq(5000L),
                eq("authorization_hold"), eq("merchant_payable"),
                eq("CAPTURE"), eq(p.getId().toString()),
                eq("Capture (webhook)"));
        }

        @Test
        @DisplayName("void with null eventId still uses payment.getId() as referenceId")
        void voidNullEventIdUsesPaymentId() {
            Payment p = paymentInStatus(PaymentStatus.AUTHORIZED, 5000);
            stubPaymentLookupWithSave(p);

            processorCaptureVoidOutboxOff.processEvent(
                null, "payment_intent.canceled", PSP_REF,
                emptyObjectNode(), null);

            verify(ledgerService).recordDoubleEntry(
                eq(p.getId()), eq(5000L),
                eq("authorization_hold"), eq("customer_receivable"),
                eq("VOID"), eq(p.getId().toString()),
                eq("Authorization void (webhook)"));
        }
    }
}
