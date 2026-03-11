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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Wave 15 Lane A — verifies dispute/chargeback webhook handling in
 * {@link WebhookEventProcessor}: charge.dispute.created, charge.dispute.updated,
 * and charge.dispute.closed.
 */
@ExtendWith(MockitoExtension.class)
class WebhookDisputeHandlingTest {

    private static final String PSP_REF = "pi_dispute_test";
    private static final String EVENT_ID = "evt_dispute_001";

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

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        processor = new WebhookEventProcessor(
            paymentRepository, processedWebhookEventRepository, refundRepository,
            ledgerEntryRepository, ledgerService, outboxService, auditLogService, meterRegistry,
            /* refundOutboxEnabled */ false,
            /* captureVoidOutboxEnabled */ false);
        ReflectionTestUtils.setField(processor, "entityManager", entityManager);
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
            || status == PaymentStatus.REFUNDED
            || status == PaymentStatus.DISPUTED;
        p.setCapturedCents(captured ? amountCents : 0);
        p.setRefundedCents(0);
        p.setCurrency("INR");
        p.setStatus(status);
        p.setIdempotencyKey("key-" + UUID.randomUUID());
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        return p;
    }

    private ObjectNode disputeObjectNode(long amount, String reason) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("amount", amount);
        if (reason != null) {
            node.put("reason", reason);
        }
        return node;
    }

    private ObjectNode disputeCloseObjectNode(long amount, String status) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("amount", amount);
        node.put("status", status);
        return node;
    }

    private ObjectNode disputeUpdateObjectNode(String status, String reason) {
        ObjectNode node = objectMapper.createObjectNode();
        if (status != null) {
            node.put("status", status);
        }
        if (reason != null) {
            node.put("reason", reason);
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
    // 1. charge.dispute.created
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("charge.dispute.created")
    class DisputeCreated {

        @Test
        @DisplayName("CAPTURED → DISPUTED happy path")
        void disputeCreated_fromCaptured_transitionsToDisputed() {
            Payment payment = paymentInStatus(PaymentStatus.CAPTURED, 10000);
            stubPaymentLookupWithSave(payment);

            processor.processEvent(EVENT_ID, "charge.dispute.created",
                PSP_REF, disputeObjectNode(10000, "fraudulent"), "{}");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DISPUTED);
        }

        @Test
        @DisplayName("PARTIALLY_REFUNDED → DISPUTED")
        void disputeCreated_fromPartiallyRefunded_transitionsToDisputed() {
            Payment payment = paymentInStatus(PaymentStatus.PARTIALLY_REFUNDED, 10000);
            payment.setRefundedCents(3000);
            stubPaymentLookupWithSave(payment);

            processor.processEvent(EVENT_ID, "charge.dispute.created",
                PSP_REF, disputeObjectNode(7000, "product_not_received"), "{}");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DISPUTED);
        }

        @Test
        @DisplayName("Already DISPUTED → idempotent no-op")
        void disputeCreated_alreadyDisputed_idempotentNoOp() {
            Payment payment = paymentInStatus(PaymentStatus.DISPUTED, 10000);
            stubPaymentLookup(payment);

            processor.processEvent(EVENT_ID, "charge.dispute.created",
                PSP_REF, disputeObjectNode(10000, "fraudulent"), "{}");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DISPUTED);
            verify(paymentRepository, never()).save(any());
            verifyNoInteractions(ledgerService);
            verifyNoInteractions(outboxService);
            verifyNoInteractions(auditLogService);
        }

        @Test
        @DisplayName("Terminal non-disputable state → rejected with metric")
        void disputeCreated_fromTerminalNonDisputable_rejected() {
            Payment payment = paymentInStatus(PaymentStatus.VOIDED, 10000);
            stubPaymentLookup(payment);

            processor.processEvent(EVENT_ID, "charge.dispute.created",
                PSP_REF, disputeObjectNode(10000, "fraudulent"), "{}");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.VOIDED);
            verify(paymentRepository, never()).save(any());
            verifyNoInteractions(ledgerService);
            assertThat(meterRegistry.counter("payment.webhook.dispute",
                "outcome", "terminal_rejected").count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Records ledger entry: debit merchant_payable, credit dispute_hold")
        void disputeCreated_recordsLedgerEntry() {
            Payment payment = paymentInStatus(PaymentStatus.CAPTURED, 8000);
            stubPaymentLookupWithSave(payment);

            processor.processEvent(EVENT_ID, "charge.dispute.created",
                PSP_REF, disputeObjectNode(8000, "fraudulent"), "{}");

            verify(ledgerService).recordDoubleEntry(
                eq(payment.getId()), eq(8000L),
                eq("merchant_payable"), eq("dispute_hold"),
                eq("DISPUTE"), eq(EVENT_ID),
                eq("Dispute created (webhook)"));
        }

        @Test
        @DisplayName("Publishes PaymentDisputed outbox event")
        @SuppressWarnings("unchecked")
        void disputeCreated_publishesOutboxEvent() {
            Payment payment = paymentInStatus(PaymentStatus.CAPTURED, 5000);
            stubPaymentLookupWithSave(payment);

            processor.processEvent(EVENT_ID, "charge.dispute.created",
                PSP_REF, disputeObjectNode(5000, "product_not_received"), "{}");

            ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(outboxService).publish(
                eq("Payment"),
                eq(payment.getId().toString()),
                eq("PaymentDisputed"),
                payloadCaptor.capture());

            Map<String, Object> payload = payloadCaptor.getValue();
            assertThat(payload).containsEntry("orderId", payment.getOrderId());
            assertThat(payload).containsEntry("paymentId", payment.getId());
            assertThat(payload).containsEntry("disputeAmountCents", 5000L);
            assertThat(payload).containsEntry("currency", "INR");
            assertThat(payload).containsEntry("reason", "product_not_received");
        }

        @Test
        @DisplayName("Records WEBHOOK_DISPUTE_CREATED audit log")
        @SuppressWarnings("unchecked")
        void disputeCreated_recordsAuditLog() {
            Payment payment = paymentInStatus(PaymentStatus.CAPTURED, 6000);
            stubPaymentLookupWithSave(payment);

            processor.processEvent(EVENT_ID, "charge.dispute.created",
                PSP_REF, disputeObjectNode(6000, "duplicate"), "{}");

            ArgumentCaptor<Map<String, Object>> detailsCaptor = ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).logSafely(
                isNull(),
                eq("WEBHOOK_DISPUTE_CREATED"),
                eq("Payment"),
                eq(payment.getId().toString()),
                detailsCaptor.capture());

            Map<String, Object> details = detailsCaptor.getValue();
            assertThat(details).containsEntry("orderId", payment.getOrderId());
            assertThat(details).containsEntry("disputeAmountCents", 6000L);
            assertThat(details).containsEntry("reason", "duplicate");
            assertThat(details).containsEntry("currency", "INR");
        }

        @Test
        @DisplayName("Metrics incremented on successful dispute creation")
        void disputeCreated_metricsIncremented() {
            Payment payment = paymentInStatus(PaymentStatus.CAPTURED, 10000);
            stubPaymentLookupWithSave(payment);

            processor.processEvent(EVENT_ID, "charge.dispute.created",
                PSP_REF, disputeObjectNode(10000, "fraudulent"), "{}");

            assertThat(meterRegistry.counter("payment.webhook.dispute",
                "outcome", "processed").count()).isEqualTo(1.0);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 2. charge.dispute.updated
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("charge.dispute.updated")
    class DisputeUpdated {

        @Test
        @DisplayName("Records audit log only, no state change")
        @SuppressWarnings("unchecked")
        void disputeUpdated_recordsAuditOnly() {
            Payment payment = paymentInStatus(PaymentStatus.DISPUTED, 10000);
            stubPaymentLookup(payment);

            processor.processEvent(EVENT_ID, "charge.dispute.updated",
                PSP_REF, disputeUpdateObjectNode("under_review", "fraudulent"), "{}");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DISPUTED);
            verify(paymentRepository, never()).save(any());
            verifyNoInteractions(ledgerService);

            verify(outboxService).publish(
                eq("Payment"),
                eq(payment.getId().toString()),
                eq("PaymentDisputeUpdated"),
                any());

            ArgumentCaptor<Map<String, Object>> detailsCaptor = ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).logSafely(
                isNull(),
                eq("WEBHOOK_DISPUTE_UPDATED"),
                eq("Payment"),
                eq(payment.getId().toString()),
                detailsCaptor.capture());

            Map<String, Object> details = detailsCaptor.getValue();
            assertThat(details).containsEntry("disputeStatus", "under_review");
            assertThat(details).containsEntry("reason", "fraudulent");

            assertThat(meterRegistry.counter("payment.webhook.dispute_update",
                "outcome", "processed").count()).isEqualTo(1.0);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 3. charge.dispute.closed
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("charge.dispute.closed")
    class DisputeClosed {

        @Test
        @DisplayName("Won: DISPUTED → CAPTURED with ledger reversal")
        void disputeClosed_won_revertsToCapture() {
            Payment payment = paymentInStatus(PaymentStatus.DISPUTED, 10000);
            stubPaymentLookupWithSave(payment);

            processor.processEvent(EVENT_ID, "charge.dispute.closed",
                PSP_REF, disputeCloseObjectNode(10000, "won"), "{}");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);

            verify(ledgerService).recordDoubleEntry(
                eq(payment.getId()), eq(10000L),
                eq("dispute_hold"), eq("merchant_payable"),
                eq("DISPUTE_REVERSAL"), eq(EVENT_ID),
                eq("Dispute won — reversal (webhook)"));

            verify(outboxService).publish(
                eq("Payment"),
                eq(payment.getId().toString()),
                eq("PaymentDisputeWon"),
                any(Map.class));

            assertThat(meterRegistry.counter("payment.webhook.dispute_close",
                "outcome", "won").count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Won: DISPUTED → PARTIALLY_REFUNDED when refundedCents > 0")
        void disputeClosed_won_revertsToPartiallyRefunded() {
            Payment payment = paymentInStatus(PaymentStatus.DISPUTED, 10000);
            payment.setRefundedCents(3000);
            stubPaymentLookupWithSave(payment);

            processor.processEvent(EVENT_ID, "charge.dispute.closed",
                PSP_REF, disputeCloseObjectNode(10000, "won"), "{}");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        }

        @Test
        @DisplayName("Lost: stays DISPUTED with loss ledger entry")
        void disputeClosed_lost_staysDisputed() {
            Payment payment = paymentInStatus(PaymentStatus.DISPUTED, 10000);
            stubPaymentLookupWithSave(payment);

            processor.processEvent(EVENT_ID, "charge.dispute.closed",
                PSP_REF, disputeCloseObjectNode(10000, "lost"), "{}");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DISPUTED);

            verify(ledgerService).recordDoubleEntry(
                eq(payment.getId()), eq(10000L),
                eq("dispute_hold"), eq("customer_receivable"),
                eq("DISPUTE_LOSS"), eq(EVENT_ID),
                eq("Dispute lost (webhook)"));

            assertThat(meterRegistry.counter("payment.webhook.dispute_close",
                "outcome", "lost").count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Lost: publishes PaymentDisputeLost outbox event")
        @SuppressWarnings("unchecked")
        void disputeClosed_lost_publishesOutboxEvent() {
            Payment payment = paymentInStatus(PaymentStatus.DISPUTED, 7500);
            stubPaymentLookupWithSave(payment);

            processor.processEvent(EVENT_ID, "charge.dispute.closed",
                PSP_REF, disputeCloseObjectNode(7500, "lost"), "{}");

            ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(outboxService).publish(
                eq("Payment"),
                eq(payment.getId().toString()),
                eq("PaymentDisputeLost"),
                payloadCaptor.capture());

            Map<String, Object> payload = payloadCaptor.getValue();
            assertThat(payload).containsEntry("orderId", payment.getOrderId());
            assertThat(payload).containsEntry("paymentId", payment.getId());
            assertThat(payload).containsEntry("amountCents", 7500L);
            assertThat(payload).containsEntry("currency", "INR");
        }

        @Test
        @DisplayName("Not DISPUTED → idempotent no-op")
        void disputeClosed_notDisputed_idempotentNoOp() {
            Payment payment = paymentInStatus(PaymentStatus.CAPTURED, 10000);
            stubPaymentLookup(payment);

            processor.processEvent(EVENT_ID, "charge.dispute.closed",
                PSP_REF, disputeCloseObjectNode(10000, "won"), "{}");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            verify(paymentRepository, never()).save(any());
            verifyNoInteractions(ledgerService);
            verifyNoInteractions(outboxService);
            verifyNoInteractions(auditLogService);
        }
    }
}
