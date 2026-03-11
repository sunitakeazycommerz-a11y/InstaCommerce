package com.instacommerce.payment.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Wave 14 Lane A — verifies that webhook handlers call
 * {@link AuditLogService#logSafely} with the expected action and details
 * after a successful state transition.
 */
@ExtendWith(MockitoExtension.class)
class WebhookAuditLoggingTest {

    private static final String PSP_REF = "pi_audit_test";
    private static final String EVENT_ID = "evt_audit_001";

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

    private ObjectNode refundObjectNode(long amountRefunded) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("amount_refunded", amountRefunded);
        node.putObject("refunds").putArray("data");
        return node;
    }

    private ObjectNode emptyObjectNode() {
        return objectMapper.createObjectNode();
    }

    // ── Captured audit ──────────────────────────────────────────────

    @Nested
    @DisplayName("handleCaptured audit logging")
    class CapturedAudit {

        @Test
        @DisplayName("logs WEBHOOK_CAPTURED with correct payment details")
        @SuppressWarnings("unchecked")
        void logsWebhookCaptured() {
            Payment payment = paymentInStatus(PaymentStatus.CAPTURE_PENDING, 5000);
            when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
            when(paymentRepository.findByPspReferenceForUpdate(PSP_REF))
                .thenReturn(Optional.of(payment));

            processor.processEvent(EVENT_ID, "payment_intent.succeeded", PSP_REF,
                captureObjectNode(5000), "{}");

            ArgumentCaptor<Map<String, Object>> detailsCaptor = ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).logSafely(
                isNull(),
                eq("WEBHOOK_CAPTURED"),
                eq("Payment"),
                eq(payment.getId().toString()),
                detailsCaptor.capture());

            Map<String, Object> details = detailsCaptor.getValue();
            assertThat(details).containsEntry("orderId", payment.getOrderId());
            assertThat(details).containsEntry("capturedCents", 5000L);
            assertThat(details).containsEntry("currency", "INR");
        }

        @Test
        @DisplayName("does not audit when payment already CAPTURED (idempotent)")
        void noAuditWhenAlreadyCaptured() {
            Payment payment = paymentInStatus(PaymentStatus.CAPTURED, 5000);
            when(paymentRepository.findByPspReferenceForUpdate(PSP_REF))
                .thenReturn(Optional.of(payment));

            processor.processEvent(EVENT_ID, "payment_intent.succeeded", PSP_REF,
                captureObjectNode(5000), "{}");

            verifyNoInteractions(auditLogService);
        }
    }

    // ── Voided audit ────────────────────────────────────────────────

    @Nested
    @DisplayName("handleVoided audit logging")
    class VoidedAudit {

        @Test
        @DisplayName("logs WEBHOOK_VOIDED with correct payment details")
        @SuppressWarnings("unchecked")
        void logsWebhookVoided() {
            Payment payment = paymentInStatus(PaymentStatus.VOID_PENDING, 8000);
            when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
            when(paymentRepository.findByPspReferenceForUpdate(PSP_REF))
                .thenReturn(Optional.of(payment));

            processor.processEvent(EVENT_ID, "payment_intent.canceled", PSP_REF,
                emptyObjectNode(), "{}");

            ArgumentCaptor<Map<String, Object>> detailsCaptor = ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).logSafely(
                isNull(),
                eq("WEBHOOK_VOIDED"),
                eq("Payment"),
                eq(payment.getId().toString()),
                detailsCaptor.capture());

            Map<String, Object> details = detailsCaptor.getValue();
            assertThat(details).containsEntry("orderId", payment.getOrderId());
            assertThat(details).containsEntry("amountCents", 8000L);
            assertThat(details).containsEntry("currency", "INR");
        }

        @Test
        @DisplayName("does not audit when payment already VOIDED (idempotent)")
        void noAuditWhenAlreadyVoided() {
            Payment payment = paymentInStatus(PaymentStatus.VOIDED, 8000);
            when(paymentRepository.findByPspReferenceForUpdate(PSP_REF))
                .thenReturn(Optional.of(payment));

            processor.processEvent(EVENT_ID, "payment_intent.canceled", PSP_REF,
                emptyObjectNode(), "{}");

            verifyNoInteractions(auditLogService);
        }
    }

    // ── Refunded audit ──────────────────────────────────────────────

    @Nested
    @DisplayName("handleRefunded audit logging")
    class RefundedAudit {

        @Test
        @DisplayName("logs WEBHOOK_REFUNDED with correct refund details")
        @SuppressWarnings("unchecked")
        void logsWebhookRefunded() {
            Payment payment = paymentInStatus(PaymentStatus.CAPTURED, 10000);
            when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
            when(paymentRepository.findByPspReferenceForUpdate(PSP_REF))
                .thenReturn(Optional.of(payment));

            processor.processEvent(EVENT_ID, "charge.refunded", PSP_REF,
                refundObjectNode(3000), "{}");

            ArgumentCaptor<Map<String, Object>> detailsCaptor = ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).logSafely(
                isNull(),
                eq("WEBHOOK_REFUNDED"),
                eq("Payment"),
                eq(payment.getId().toString()),
                detailsCaptor.capture());

            Map<String, Object> details = detailsCaptor.getValue();
            assertThat(details).containsEntry("orderId", payment.getOrderId());
            assertThat(details).containsEntry("refundedCents", 3000L);
            assertThat(details).containsEntry("status", "PARTIALLY_REFUNDED");
            assertThat(details).containsEntry("currency", "INR");
        }

        @Test
        @DisplayName("does not audit when refund delta is zero (no-op)")
        void noAuditWhenRefundDeltaZero() {
            Payment payment = paymentInStatus(PaymentStatus.CAPTURED, 10000);
            payment.setRefundedCents(3000);
            when(paymentRepository.findByPspReferenceForUpdate(PSP_REF))
                .thenReturn(Optional.of(payment));

            // amount_refunded == existing refundedCents → delta is zero
            processor.processEvent(EVENT_ID, "charge.refunded", PSP_REF,
                refundObjectNode(3000), "{}");

            verifyNoInteractions(auditLogService);
        }
    }

    // ── Audit failure resilience ────────────────────────────────────

    @Nested
    @DisplayName("audit call happens after state persistence")
    class AuditFailureResilience {

        @Test
        @DisplayName("handleCaptured persists state before calling auditLogService")
        void captureAuditAfterSave() {
            Payment payment = paymentInStatus(PaymentStatus.CAPTURE_PENDING, 5000);
            when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
            when(paymentRepository.findByPspReferenceForUpdate(PSP_REF))
                .thenReturn(Optional.of(payment));

            processor.processEvent(EVENT_ID, "payment_intent.succeeded", PSP_REF,
                captureObjectNode(5000), "{}");

            // Verify save happens BEFORE audit — so even if logSafely were to
            // fail, the payment state transition is already persisted.
            InOrder inOrder = inOrder(paymentRepository, auditLogService);
            inOrder.verify(paymentRepository).save(any(Payment.class));
            inOrder.verify(auditLogService).logSafely(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("handleVoided persists state before calling auditLogService")
        void voidAuditAfterSave() {
            Payment payment = paymentInStatus(PaymentStatus.VOID_PENDING, 8000);
            when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
            when(paymentRepository.findByPspReferenceForUpdate(PSP_REF))
                .thenReturn(Optional.of(payment));

            processor.processEvent(EVENT_ID, "payment_intent.canceled", PSP_REF,
                emptyObjectNode(), "{}");

            InOrder inOrder = inOrder(paymentRepository, auditLogService);
            inOrder.verify(paymentRepository).save(any(Payment.class));
            inOrder.verify(auditLogService).logSafely(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("handleRefunded persists state before calling auditLogService")
        void refundAuditAfterSave() {
            Payment payment = paymentInStatus(PaymentStatus.CAPTURED, 10000);
            when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
            when(paymentRepository.findByPspReferenceForUpdate(PSP_REF))
                .thenReturn(Optional.of(payment));

            processor.processEvent(EVENT_ID, "charge.refunded", PSP_REF,
                refundObjectNode(3000), "{}");

            InOrder inOrder = inOrder(paymentRepository, auditLogService);
            inOrder.verify(paymentRepository).save(any(Payment.class));
            inOrder.verify(auditLogService).logSafely(any(), any(), any(), any(), any());
        }
    }
}
