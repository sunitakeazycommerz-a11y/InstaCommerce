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
import com.instacommerce.payment.domain.model.Refund;
import com.instacommerce.payment.domain.model.RefundStatus;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Wave 15 Lane C — verifies missing webhook handlers added to
 * {@link WebhookEventProcessor}: charge.refund.updated, AUTHORIZE_PENDING
 * voidability, and charge.expired.
 */
@ExtendWith(MockitoExtension.class)
class WebhookMissingHandlersTest {

    private static final String PSP_REF = "pi_missing_handlers_test";
    private static final String EVENT_ID = "evt_missing_001";

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
    private WebhookEventProcessor processor;
    private WebhookEventProcessor processorOutboxEnabled;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        processor = new WebhookEventProcessor(
            paymentRepository, processedWebhookEventRepository, refundRepository,
            ledgerEntryRepository, ledgerService, outboxService, auditLogService, meterRegistry,
            /* refundOutboxEnabled */ false,
            /* captureVoidOutboxEnabled */ false);
        ReflectionTestUtils.setField(processor, "entityManager", entityManager);

        processorOutboxEnabled = new WebhookEventProcessor(
            paymentRepository, processedWebhookEventRepository, refundRepository,
            ledgerEntryRepository, ledgerService, outboxService, auditLogService, meterRegistry,
            /* refundOutboxEnabled */ false,
            /* captureVoidOutboxEnabled */ true);
        ReflectionTestUtils.setField(processorOutboxEnabled, "entityManager", entityManager);
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

    private Refund refundInStatus(UUID paymentId, RefundStatus status, String pspRefundId) {
        Refund r = new Refund();
        r.setId(UUID.randomUUID());
        r.setPaymentId(paymentId);
        r.setAmountCents(5000);
        r.setPspRefundId(pspRefundId);
        r.setIdempotencyKey("rkey-" + UUID.randomUUID());
        r.setStatus(status);
        r.setCreatedAt(Instant.now());
        r.setUpdatedAt(Instant.now());
        return r;
    }

    private ObjectNode refundUpdateObjectNode(String id, String status) {
        ObjectNode node = objectMapper.createObjectNode();
        if (id != null) {
            node.put("id", id);
        }
        if (status != null) {
            node.put("status", status);
        }
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
    // 1. charge.refund.updated
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("charge.refund.updated")
    class RefundUpdated {

        @Test
        @DisplayName("PSP failed + local COMPLETED → logs critical alert")
        @SuppressWarnings("unchecked")
        void refundUpdated_pspFailed_localCompleted_logsAlert() {
            Payment payment = paymentInStatus(PaymentStatus.CAPTURED, 10000);
            stubPaymentLookup(payment);
            String pspRefundId = "re_test_failed_001";
            Refund tracked = refundInStatus(payment.getId(), RefundStatus.COMPLETED, pspRefundId);
            when(refundRepository.findByPspRefundId(pspRefundId)).thenReturn(Optional.of(tracked));

            processor.processEvent(EVENT_ID, "charge.refund.updated",
                PSP_REF, refundUpdateObjectNode(pspRefundId, "failed"), "{}");

            ArgumentCaptor<Map<String, Object>> detailsCaptor = ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).logSafely(
                isNull(),
                eq("WEBHOOK_REFUND_PSP_FAILURE"),
                eq("Refund"),
                eq(tracked.getId().toString()),
                detailsCaptor.capture());

            Map<String, Object> details = detailsCaptor.getValue();
            assertThat(details).containsEntry("pspRefundId", pspRefundId);
            assertThat(details).containsEntry("localStatus", "COMPLETED");
            assertThat(details).containsEntry("pspStatus", "failed");

            assertThat(meterRegistry.counter("payment.webhook.refund_update",
                "outcome", "psp_reversal_detected").count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("PSP succeeded + local PENDING → logs info, still records audit")
        void refundUpdated_pspSucceeded_localPending_logsInfo() {
            Payment payment = paymentInStatus(PaymentStatus.CAPTURED, 10000);
            stubPaymentLookup(payment);
            String pspRefundId = "re_test_succ_001";
            Refund tracked = refundInStatus(payment.getId(), RefundStatus.PENDING, pspRefundId);
            when(refundRepository.findByPspRefundId(pspRefundId)).thenReturn(Optional.of(tracked));

            processor.processEvent(EVENT_ID, "charge.refund.updated",
                PSP_REF, refundUpdateObjectNode(pspRefundId, "succeeded"), "{}");

            assertThat(meterRegistry.counter("payment.webhook.refund_update",
                "outcome", "pending_succeeded").count()).isEqualTo(1.0);
            assertThat(meterRegistry.counter("payment.webhook.refund_update",
                "outcome", "processed").count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Untracked pspRefundId → skips with metric")
        void refundUpdated_untrackedRefund_skips() {
            Payment payment = paymentInStatus(PaymentStatus.CAPTURED, 10000);
            stubPaymentLookup(payment);
            String pspRefundId = "re_unknown_999";
            when(refundRepository.findByPspRefundId(pspRefundId)).thenReturn(Optional.empty());

            processor.processEvent(EVENT_ID, "charge.refund.updated",
                PSP_REF, refundUpdateObjectNode(pspRefundId, "succeeded"), "{}");

            verifyNoInteractions(auditLogService);
            assertThat(meterRegistry.counter("payment.webhook.refund_update",
                "outcome", "untracked").count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Missing refund ID in payload → skips with metric")
        void refundUpdated_missingRefundId_skips() {
            Payment payment = paymentInStatus(PaymentStatus.CAPTURED, 10000);
            stubPaymentLookup(payment);

            processor.processEvent(EVENT_ID, "charge.refund.updated",
                PSP_REF, refundUpdateObjectNode(null, "failed"), "{}");

            verifyNoInteractions(auditLogService);
            verifyNoInteractions(refundRepository);
            assertThat(meterRegistry.counter("payment.webhook.refund_update",
                "outcome", "missing_id").count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("General case records WEBHOOK_REFUND_UPDATED audit log")
        @SuppressWarnings("unchecked")
        void refundUpdated_recordsAuditLog() {
            Payment payment = paymentInStatus(PaymentStatus.CAPTURED, 10000);
            stubPaymentLookup(payment);
            String pspRefundId = "re_test_general_001";
            Refund tracked = refundInStatus(payment.getId(), RefundStatus.PENDING, pspRefundId);
            when(refundRepository.findByPspRefundId(pspRefundId)).thenReturn(Optional.of(tracked));

            processor.processEvent(EVENT_ID, "charge.refund.updated",
                PSP_REF, refundUpdateObjectNode(pspRefundId, "pending"), "{}");

            ArgumentCaptor<Map<String, Object>> detailsCaptor = ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).logSafely(
                isNull(),
                eq("WEBHOOK_REFUND_UPDATED"),
                eq("Refund"),
                eq(tracked.getId().toString()),
                detailsCaptor.capture());

            Map<String, Object> details = detailsCaptor.getValue();
            assertThat(details).containsEntry("paymentId", tracked.getPaymentId());
            assertThat(details).containsEntry("pspRefundId", pspRefundId);
            assertThat(details).containsEntry("pspStatus", "pending");
            assertThat(details).containsEntry("localStatus", "PENDING");

            assertThat(meterRegistry.counter("payment.webhook.refund_update",
                "outcome", "processed").count()).isEqualTo(1.0);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 2. AUTHORIZE_PENDING voidability
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AUTHORIZE_PENDING voidability")
    class AuthorizePendingVoid {

        @Test
        @DisplayName("AUTHORIZE_PENDING → VOIDED, no auth hold → skips ledger")
        void voided_fromAuthorizePending_noAuthHold_skipsLedger() {
            Payment payment = paymentInStatus(PaymentStatus.AUTHORIZE_PENDING, 10000);
            stubPaymentLookupWithSave(payment);
            when(ledgerEntryRepository.existsByPaymentIdAndReferenceTypeAndReferenceId(
                payment.getId(), "AUTHORIZATION", payment.getId().toString()))
                .thenReturn(false);

            processor.processEvent(EVENT_ID, "payment_intent.canceled",
                PSP_REF, objectMapper.createObjectNode(), "{}");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.VOIDED);
            verifyNoInteractions(ledgerService);
        }

        @Test
        @DisplayName("AUTHORIZE_PENDING → VOIDED, with auth hold → records ledger")
        void voided_fromAuthorizePending_withAuthHold_recordsLedger() {
            Payment payment = paymentInStatus(PaymentStatus.AUTHORIZE_PENDING, 10000);
            stubPaymentLookupWithSave(payment);
            when(ledgerEntryRepository.existsByPaymentIdAndReferenceTypeAndReferenceId(
                payment.getId(), "AUTHORIZATION", payment.getId().toString()))
                .thenReturn(true);

            processor.processEvent(EVENT_ID, "payment_intent.canceled",
                PSP_REF, objectMapper.createObjectNode(), "{}");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.VOIDED);
            verify(ledgerService).recordDoubleEntry(
                eq(payment.getId()), eq(10000L),
                eq("authorization_hold"), eq("customer_receivable"),
                eq("VOID"), eq(payment.getId().toString()),
                eq("Authorization void (webhook)"));
        }

        @Test
        @DisplayName("AUTHORIZED → VOIDED always records ledger (existing behavior)")
        void voided_fromAuthorized_alwaysRecordsLedger() {
            Payment payment = paymentInStatus(PaymentStatus.AUTHORIZED, 10000);
            stubPaymentLookupWithSave(payment);

            processor.processEvent(EVENT_ID, "payment_intent.canceled",
                PSP_REF, objectMapper.createObjectNode(), "{}");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.VOIDED);
            verify(ledgerService).recordDoubleEntry(
                eq(payment.getId()), eq(10000L),
                eq("authorization_hold"), eq("customer_receivable"),
                eq("VOID"), eq(payment.getId().toString()),
                eq("Authorization void (webhook)"));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 3. charge.expired
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("charge.expired")
    class ChargeExpired {

        @Test
        @DisplayName("AUTHORIZED → VOIDED with auth hold release")
        void expired_fromAuthorized_transitionsToVoided() {
            Payment payment = paymentInStatus(PaymentStatus.AUTHORIZED, 10000);
            stubPaymentLookupWithSave(payment);
            when(ledgerEntryRepository.existsByPaymentIdAndReferenceTypeAndReferenceId(
                payment.getId(), "AUTHORIZATION", payment.getId().toString()))
                .thenReturn(true);

            processor.processEvent(EVENT_ID, "charge.expired",
                PSP_REF, objectMapper.createObjectNode(), "{}");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.VOIDED);
            verify(ledgerService).recordDoubleEntry(
                eq(payment.getId()), eq(10000L),
                eq("authorization_hold"), eq("customer_receivable"),
                eq("VOID"), eq(payment.getId().toString()),
                eq("Authorization expired (webhook)"));
            assertThat(meterRegistry.counter("payment.webhook.expired",
                "outcome", "processed").count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("AUTHORIZE_PENDING → VOIDED, no auth hold to release")
        void expired_fromAuthorizePending_transitionsToVoided() {
            Payment payment = paymentInStatus(PaymentStatus.AUTHORIZE_PENDING, 10000);
            stubPaymentLookupWithSave(payment);
            when(ledgerEntryRepository.existsByPaymentIdAndReferenceTypeAndReferenceId(
                payment.getId(), "AUTHORIZATION", payment.getId().toString()))
                .thenReturn(false);

            processor.processEvent(EVENT_ID, "charge.expired",
                PSP_REF, objectMapper.createObjectNode(), "{}");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.VOIDED);
            verifyNoInteractions(ledgerService);
        }

        @Test
        @DisplayName("CAPTURE_PENDING → VOIDED with auth hold release")
        void expired_fromCapturePending_transitionsToVoided() {
            Payment payment = paymentInStatus(PaymentStatus.CAPTURE_PENDING, 10000);
            stubPaymentLookupWithSave(payment);
            when(ledgerEntryRepository.existsByPaymentIdAndReferenceTypeAndReferenceId(
                payment.getId(), "AUTHORIZATION", payment.getId().toString()))
                .thenReturn(true);

            processor.processEvent(EVENT_ID, "charge.expired",
                PSP_REF, objectMapper.createObjectNode(), "{}");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.VOIDED);
            verify(ledgerService).recordDoubleEntry(
                eq(payment.getId()), eq(10000L),
                eq("authorization_hold"), eq("customer_receivable"),
                eq("VOID"), eq(payment.getId().toString()),
                eq("Authorization expired (webhook)"));
        }

        @Test
        @DisplayName("CAPTURED → ignored (no-op)")
        void expired_fromCaptured_ignored() {
            Payment payment = paymentInStatus(PaymentStatus.CAPTURED, 10000);
            stubPaymentLookup(payment);

            processor.processEvent(EVENT_ID, "charge.expired",
                PSP_REF, objectMapper.createObjectNode(), "{}");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            verify(paymentRepository, never()).save(any());
            verifyNoInteractions(ledgerService);
            verifyNoInteractions(outboxService);
        }

        @Test
        @DisplayName("Already VOIDED → idempotent no-op")
        void expired_alreadyVoided_idempotent() {
            Payment payment = paymentInStatus(PaymentStatus.VOIDED, 10000);
            stubPaymentLookup(payment);

            processor.processEvent(EVENT_ID, "charge.expired",
                PSP_REF, objectMapper.createObjectNode(), "{}");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.VOIDED);
            verify(paymentRepository, never()).save(any());
            verifyNoInteractions(ledgerService);
        }

        @Test
        @DisplayName("Publishes outbox when captureVoidOutboxEnabled")
        @SuppressWarnings("unchecked")
        void expired_publishesOutboxWhenEnabled() {
            Payment payment = paymentInStatus(PaymentStatus.AUTHORIZED, 10000);
            stubPaymentLookupWithSave(payment);
            when(ledgerEntryRepository.existsByPaymentIdAndReferenceTypeAndReferenceId(
                payment.getId(), "AUTHORIZATION", payment.getId().toString()))
                .thenReturn(true);

            processorOutboxEnabled.processEvent(EVENT_ID, "charge.expired",
                PSP_REF, objectMapper.createObjectNode(), "{}");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.VOIDED);

            ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(outboxService).publish(
                eq("Payment"),
                eq(payment.getId().toString()),
                eq("PaymentVoided"),
                payloadCaptor.capture());

            Map<String, Object> payload = payloadCaptor.getValue();
            assertThat(payload).containsEntry("orderId", payment.getOrderId());
            assertThat(payload).containsEntry("paymentId", payment.getId());
            assertThat(payload).containsEntry("amountCents", 10000L);
            assertThat(payload).containsEntry("currency", "INR");
        }
    }
}
