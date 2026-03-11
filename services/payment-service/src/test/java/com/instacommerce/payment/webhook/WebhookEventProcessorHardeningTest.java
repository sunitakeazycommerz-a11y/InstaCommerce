package com.instacommerce.payment.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.mockito.ArgumentCaptor;
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
    @Mock LedgerEntryRepository ledgerEntryRepository;
    @Mock LedgerService ledgerService;
    @Mock OutboxService outboxService;
    @Mock AuditLogService auditLogService;
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
            ledgerEntryRepository, ledgerService, outboxService, auditLogService, meterRegistry,
            /* refundOutboxEnabled */ false,
            /* captureVoidOutboxEnabled */ true);
        processorCaptureVoidOutboxOff = new WebhookEventProcessor(
            paymentRepository, processedWebhookEventRepository, refundRepository,
            ledgerEntryRepository, ledgerService, outboxService, auditLogService, meterRegistry,
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
            names = {"VOID_PENDING"})
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
            verifyNoInteractions(ledgerService, outboxService, auditLogService);
            assertThat(meterRegistry.counter(
                "payment.webhook.fail", "outcome", "terminal_rejected").count())
                .isEqualTo(1.0);
        }

        @ParameterizedTest(name = "failed processes when payment in {0}")
        @EnumSource(value = PaymentStatus.class,
            names = {"AUTHORIZE_PENDING", "AUTHORIZED", "CAPTURE_PENDING", "VOID_PENDING"})
        @DisplayName("failed marks eligible states as FAILED and publishes PaymentFailed outbox")
        void failedProcessesEligibleStates(PaymentStatus status) {
            Payment p = paymentInStatus(status, 5000);
            stubPaymentLookupWithSave(p);

            processorCaptureVoidOutboxOff.processEvent(
                EVENT_ID, "payment_intent.payment_failed", PSP_REF,
                emptyObjectNode(), null);

            assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
            verify(paymentRepository).save(p);
            // No auth hold ledger entry exists (mock default false), so no ledger interaction
            verifyNoInteractions(ledgerService);
            verify(outboxService).publish(
                eq("Payment"), eq(p.getId().toString()),
                eq("PaymentFailed"), any());
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

    // ═══════════════════════════════════════════════════════════════
    // 4. Wave 11 — Failure parity: auth release, outbox, audit
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Wave 11: Failure auth release, PaymentFailed outbox, audit")
    class FailureParityWave11 {

        @Test
        @DisplayName("AUTHORIZE_PENDING failure does NOT release auth hold (no hold recorded yet)")
        void failFromAuthorizePending_noLedgerRelease() {
            Payment p = paymentInStatus(PaymentStatus.AUTHORIZE_PENDING, 5000);
            stubPaymentLookupWithSave(p);

            processorCaptureVoidOutboxOff.processEvent(
                EVENT_ID, "payment_intent.payment_failed", PSP_REF,
                emptyObjectNode(), null);

            assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
            verifyNoInteractions(ledgerService, ledgerEntryRepository);
            verify(outboxService).publish(
                eq("Payment"), eq(p.getId().toString()),
                eq("PaymentFailed"), any());
            ArgumentCaptor<Map<String, Object>> auditDetailsCaptor = ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).logSafely(
                isNull(),
                eq("PAYMENT_FAILED"),
                eq("Payment"),
                eq(p.getId().toString()),
                auditDetailsCaptor.capture());
            assertThat(auditDetailsCaptor.getValue())
                .containsEntry("orderId", p.getOrderId())
                .containsEntry("previousStatus", PaymentStatus.AUTHORIZE_PENDING.name())
                .containsEntry("authReleased", false);
            assertThat(meterRegistry.counter(
                "payment.webhook.fail", "outcome", "auth_released").count())
                .isEqualTo(0.0);
        }

        @ParameterizedTest(name = "failure from {0} releases auth hold when ledger entry exists")
        @EnumSource(value = PaymentStatus.class,
            names = {"AUTHORIZED", "CAPTURE_PENDING", "VOID_PENDING"})
        @DisplayName("post-authorization failure releases auth hold")
        void failFromPostAuthState_releasesAuthHold(PaymentStatus status) {
            Payment p = paymentInStatus(status, 7000);
            stubPaymentLookupWithSave(p);
            when(ledgerEntryRepository.existsByPaymentIdAndReferenceTypeAndReferenceId(
                p.getId(), "AUTHORIZATION", p.getId().toString())).thenReturn(true);

            processorCaptureVoidOutboxOff.processEvent(
                EVENT_ID, "payment_intent.payment_failed", PSP_REF,
                emptyObjectNode(), null);

            assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
            verify(ledgerService).recordDoubleEntry(
                eq(p.getId()), eq(7000L),
                eq("authorization_hold"), eq("customer_receivable"),
                eq("FAILURE_RELEASE"), eq(p.getId().toString()),
                eq("Authorization release on failure (webhook)"));
            verify(outboxService).publish(
                eq("Payment"), eq(p.getId().toString()),
                eq("PaymentFailed"), any());
            ArgumentCaptor<Map<String, Object>> auditDetailsCaptor = ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).logSafely(
                isNull(),
                eq("PAYMENT_FAILED"),
                eq("Payment"),
                eq(p.getId().toString()),
                auditDetailsCaptor.capture());
            assertThat(auditDetailsCaptor.getValue())
                .containsEntry("orderId", p.getOrderId())
                .containsEntry("previousStatus", status.name())
                .containsEntry("authReleased", true);
            assertThat(meterRegistry.counter(
                "payment.webhook.fail", "outcome", "auth_released").count())
                .isEqualTo(1.0);
            assertThat(meterRegistry.counter(
                "payment.webhook.fail", "outcome", "processed").count())
                .isEqualTo(1.0);
        }

        @ParameterizedTest(name = "failure from {0} skips auth release when no ledger entry exists")
        @EnumSource(value = PaymentStatus.class,
            names = {"AUTHORIZED", "CAPTURE_PENDING", "VOID_PENDING"})
        @DisplayName("post-authorization failure without auth ledger entry skips release")
        void failFromPostAuthState_noAuthEntry_skipsRelease(PaymentStatus status) {
            Payment p = paymentInStatus(status, 7000);
            stubPaymentLookupWithSave(p);
            when(ledgerEntryRepository.existsByPaymentIdAndReferenceTypeAndReferenceId(
                p.getId(), "AUTHORIZATION", p.getId().toString())).thenReturn(false);

            processorCaptureVoidOutboxOff.processEvent(
                EVENT_ID, "payment_intent.payment_failed", PSP_REF,
                emptyObjectNode(), null);

            assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
            verifyNoInteractions(ledgerService);
            verify(outboxService).publish(
                eq("Payment"), eq(p.getId().toString()),
                eq("PaymentFailed"), any());
            verify(auditLogService).logSafely(
                isNull(),
                eq("PAYMENT_FAILED"),
                eq("Payment"),
                eq(p.getId().toString()),
                any());
            assertThat(meterRegistry.counter(
                "payment.webhook.fail", "outcome", "auth_released").count())
                .isEqualTo(0.0);
        }

        @Test
        @DisplayName("idempotent retry: already FAILED returns silently — no duplicate ledger/outbox")
        void alreadyFailed_idempotentSkip() {
            Payment p = paymentInStatus(PaymentStatus.FAILED, 5000);
            stubPaymentLookup(p);

            processorCaptureVoidOutboxOff.processEvent(
                EVENT_ID, "payment_intent.payment_failed", PSP_REF,
                emptyObjectNode(), null);

            assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
            verify(paymentRepository, never()).save(any());
            verifyNoInteractions(ledgerService, ledgerEntryRepository, outboxService, auditLogService);
        }

        @Test
        @DisplayName("retry with auth release uses dedup-safe ledger referenceId")
        void retryAuthRelease_dedupSafe() {
            Payment p = paymentInStatus(PaymentStatus.AUTHORIZED, 5000);
            stubPaymentLookupWithSave(p);
            when(ledgerEntryRepository.existsByPaymentIdAndReferenceTypeAndReferenceId(
                p.getId(), "AUTHORIZATION", p.getId().toString())).thenReturn(true);

            // First call
            processorCaptureVoidOutboxOff.processEvent(
                EVENT_ID, "payment_intent.payment_failed", PSP_REF,
                emptyObjectNode(), null);

            // Verify FAILURE_RELEASE uses payment.getId() as referenceId
            // (LedgerService.recordDoubleEntry dedups on paymentId+referenceType+referenceId)
            verify(ledgerService).recordDoubleEntry(
                eq(p.getId()), eq(5000L),
                eq("authorization_hold"), eq("customer_receivable"),
                eq("FAILURE_RELEASE"), eq(p.getId().toString()),
                eq("Authorization release on failure (webhook)"));
        }

        @Test
        @DisplayName("PaymentFailed outbox payload includes reason from PSP error message")
        @SuppressWarnings("unchecked")
        void outboxPayload_includesPspReason() {
            Payment p = paymentInStatus(PaymentStatus.AUTHORIZED, 5000);
            stubPaymentLookupWithSave(p);

            ObjectNode node = objectMapper.createObjectNode();
            node.put("id", PSP_REF);
            ObjectNode error = node.putObject("last_payment_error");
            error.put("message", "Your card was declined");
            error.put("code", "card_declined");

            processorCaptureVoidOutboxOff.processEvent(
                EVENT_ID, "payment_intent.payment_failed", PSP_REF,
                node, null);

            ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(outboxService).publish(
                eq("Payment"), eq(p.getId().toString()),
                eq("PaymentFailed"), payloadCaptor.capture());
            Map<String, Object> payload = payloadCaptor.getValue();
            assertThat(payload.get("orderId")).isEqualTo(p.getOrderId());
            assertThat(payload.get("paymentId")).isEqualTo(p.getId());
            assertThat(payload.get("reason")).isEqualTo("Your card was declined");
        }

        @Test
        @DisplayName("PaymentFailed outbox uses PSP error code when message is absent")
        @SuppressWarnings("unchecked")
        void outboxPayload_fallsToPspCode() {
            Payment p = paymentInStatus(PaymentStatus.CAPTURE_PENDING, 5000);
            stubPaymentLookupWithSave(p);

            ObjectNode node = objectMapper.createObjectNode();
            node.put("id", PSP_REF);
            ObjectNode error = node.putObject("last_payment_error");
            error.put("code", "expired_card");

            processorCaptureVoidOutboxOff.processEvent(
                EVENT_ID, "payment_intent.payment_failed", PSP_REF,
                node, null);

            ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(outboxService).publish(
                eq("Payment"), eq(p.getId().toString()),
                eq("PaymentFailed"), payloadCaptor.capture());
            assertThat(payloadCaptor.getValue().get("reason")).isEqualTo("PSP error: expired_card");
        }

        @Test
        @DisplayName("PaymentFailed outbox falls back to generic reason when no PSP error")
        @SuppressWarnings("unchecked")
        void outboxPayload_fallsToGenericReason() {
            Payment p = paymentInStatus(PaymentStatus.VOID_PENDING, 5000);
            stubPaymentLookupWithSave(p);

            processorCaptureVoidOutboxOff.processEvent(
                EVENT_ID, "payment_intent.payment_failed", PSP_REF,
                emptyObjectNode(), null);

            ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(outboxService).publish(
                eq("Payment"), eq(p.getId().toString()),
                eq("PaymentFailed"), payloadCaptor.capture());
            assertThat(payloadCaptor.getValue().get("reason"))
                .isEqualTo("Payment failed during VOID_PENDING");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 5. Wave 12 — Partial capture: authorization remainder release
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Wave 12: Partial capture releases uncaptured authorization remainder")
    class PartialCaptureReleaseWave12 {

        @Test
        @DisplayName("partial capture records CAPTURE for captured amount and PARTIAL_CAPTURE_RELEASE for remainder")
        void partialCapture_recordsCaptureAndReleasesRemainder() {
            Payment p = paymentInStatus(PaymentStatus.AUTHORIZED, 10000);
            stubPaymentLookupWithSave(p);

            processorCaptureVoidOutboxOff.processEvent(
                EVENT_ID, "payment_intent.succeeded", PSP_REF,
                captureObjectNode(7000), null);

            assertThat(p.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(p.getCapturedCents()).isEqualTo(7000);

            // CAPTURE for the captured delta
            verify(ledgerService).recordDoubleEntry(
                eq(p.getId()), eq(7000L),
                eq("authorization_hold"), eq("merchant_payable"),
                eq("CAPTURE"), eq(p.getId().toString()),
                eq("Capture (webhook)"));

            // PARTIAL_CAPTURE_RELEASE for the uncaptured remainder
            verify(ledgerService).recordDoubleEntry(
                eq(p.getId()), eq(3000L),
                eq("authorization_hold"), eq("customer_receivable"),
                eq("PARTIAL_CAPTURE_RELEASE"), eq(p.getId().toString()),
                eq("Partial capture remainder release (webhook)"));

            assertThat(meterRegistry.counter(
                "payment.webhook.capture", "outcome", "partial_capture_released").count())
                .isEqualTo(1.0);
            assertThat(meterRegistry.counter(
                "payment.webhook.capture", "outcome", "processed").count())
                .isEqualTo(1.0);
        }

        @Test
        @DisplayName("full capture does NOT emit PARTIAL_CAPTURE_RELEASE")
        void fullCapture_noPartialRelease() {
            Payment p = paymentInStatus(PaymentStatus.AUTHORIZED, 5000);
            stubPaymentLookupWithSave(p);

            processorCaptureVoidOutboxOff.processEvent(
                EVENT_ID, "payment_intent.succeeded", PSP_REF,
                captureObjectNode(5000), null);

            assertThat(p.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(p.getCapturedCents()).isEqualTo(5000);

            // Only CAPTURE ledger entry, no PARTIAL_CAPTURE_RELEASE
            verify(ledgerService).recordDoubleEntry(
                eq(p.getId()), eq(5000L),
                eq("authorization_hold"), eq("merchant_payable"),
                eq("CAPTURE"), eq(p.getId().toString()),
                eq("Capture (webhook)"));

            // Verify recordDoubleEntry was called exactly once (only CAPTURE)
            verify(ledgerService, never()).recordDoubleEntry(
                any(), any(long.class),
                any(), any(),
                eq("PARTIAL_CAPTURE_RELEASE"), any(),
                any());

            assertThat(meterRegistry.counter(
                "payment.webhook.capture", "outcome", "partial_capture_released").count())
                .isEqualTo(0.0);
        }

        @Test
        @DisplayName("already-CAPTURED payment returns early — no duplicate CAPTURE or PARTIAL_CAPTURE_RELEASE")
        void alreadyCaptured_idempotentSkip() {
            Payment p = paymentInStatus(PaymentStatus.CAPTURED, 10000);
            p.setCapturedCents(7000); // Previously partially captured
            stubPaymentLookup(p);

            processorCaptureVoidOutboxOff.processEvent(
                EVENT_ID, "payment_intent.succeeded", PSP_REF,
                captureObjectNode(7000), null);

            assertThat(p.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            verify(paymentRepository, never()).save(any());
            verifyNoInteractions(ledgerService, outboxService);
        }

        @Test
        @DisplayName("partial capture from CAPTURE_PENDING also releases remainder")
        void partialCapture_fromCapturePending() {
            Payment p = paymentInStatus(PaymentStatus.CAPTURE_PENDING, 8000);
            stubPaymentLookupWithSave(p);

            processorCaptureVoidOutboxOff.processEvent(
                EVENT_ID, "payment_intent.succeeded", PSP_REF,
                captureObjectNode(5000), null);

            assertThat(p.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(p.getCapturedCents()).isEqualTo(5000);

            verify(ledgerService).recordDoubleEntry(
                eq(p.getId()), eq(5000L),
                eq("authorization_hold"), eq("merchant_payable"),
                eq("CAPTURE"), eq(p.getId().toString()),
                eq("Capture (webhook)"));

            verify(ledgerService).recordDoubleEntry(
                eq(p.getId()), eq(3000L),
                eq("authorization_hold"), eq("customer_receivable"),
                eq("PARTIAL_CAPTURE_RELEASE"), eq(p.getId().toString()),
                eq("Partial capture remainder release (webhook)"));
        }

        @Test
        @DisplayName("capture with no amount_received falls back to full amount — no remainder release")
        void captureNoAmount_fallsBackToFull_noRelease() {
            Payment p = paymentInStatus(PaymentStatus.AUTHORIZED, 6000);
            stubPaymentLookupWithSave(p);

            // objectNode without amount_received or amount fields
            processorCaptureVoidOutboxOff.processEvent(
                EVENT_ID, "payment_intent.succeeded", PSP_REF,
                emptyObjectNode(), null);

            assertThat(p.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(p.getCapturedCents()).isEqualTo(6000);

            verify(ledgerService).recordDoubleEntry(
                eq(p.getId()), eq(6000L),
                eq("authorization_hold"), eq("merchant_payable"),
                eq("CAPTURE"), eq(p.getId().toString()),
                eq("Capture (webhook)"));

            verify(ledgerService, never()).recordDoubleEntry(
                any(), any(long.class),
                any(), any(),
                eq("PARTIAL_CAPTURE_RELEASE"), any(),
                any());
        }
    }
}
