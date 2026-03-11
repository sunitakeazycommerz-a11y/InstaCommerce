package com.instacommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.domain.model.Refund;
import com.instacommerce.payment.domain.model.RefundStatus;
import com.instacommerce.payment.exception.PaymentNotFoundException;
import com.instacommerce.payment.repository.PaymentRepository;
import com.instacommerce.payment.repository.RefundRepository;
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
 * Focused tests for the {@code PaymentRefundFailed} outbox event emitted by
 * {@link RefundTransactionHelper#markRefundFailed} and
 * {@link RefundTransactionHelper#resolveStaleRefundFailed}.
 *
 * Verifies:
 * <ul>
 *   <li>Exact payload shape (orderId, paymentId, refundId, amountCents, currency, reason, failureSource)</li>
 *   <li>Correct failureSource per path (gateway vs recovery)</li>
 *   <li>No event published when CAS loses (concurrent writer wins)</li>
 *   <li>PaymentNotFoundException when payment lookup fails</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class RefundTransactionHelperRefundFailedEventTest {

    @Mock RefundRepository refundRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock LedgerService ledgerService;
    @Mock OutboxService outboxService;
    @Mock AuditLogService auditLogService;
    @Mock EntityManager entityManager;

    private RefundTransactionHelper helper;

    @BeforeEach
    void setUp() {
        helper = new RefundTransactionHelper(
            refundRepository, paymentRepository, ledgerService, outboxService, auditLogService);
        ReflectionTestUtils.setField(helper, "entityManager", entityManager);
    }

    // --- Helpers ---

    private Refund pendingRefund(UUID paymentId) {
        Refund r = new Refund();
        r.setId(UUID.randomUUID());
        r.setPaymentId(paymentId);
        r.setAmountCents(3200);
        r.setReason("damaged item");
        r.setIdempotencyKey("idem-" + UUID.randomUUID());
        r.setStatus(RefundStatus.PENDING);
        r.setCreatedAt(Instant.now());
        r.setUpdatedAt(Instant.now());
        return r;
    }

    private Payment capturedPayment() {
        Payment p = new Payment();
        p.setId(UUID.randomUUID());
        p.setOrderId(UUID.randomUUID());
        p.setAmountCents(10_000);
        p.setCapturedCents(10_000);
        p.setRefundedCents(0);
        p.setCurrency("INR");
        p.setStatus(PaymentStatus.CAPTURED);
        p.setPspReference("psp-ref-" + UUID.randomUUID());
        return p;
    }

    // --- markRefundFailed: PaymentRefundFailed event ---

    @Nested
    @DisplayName("markRefundFailed: PaymentRefundFailed event")
    class MarkRefundFailedEvent {

        @Test
        @DisplayName("CAS success → publishes PaymentRefundFailed with failureSource=gateway and exact payload shape")
        void casSuccess_publishesEventWithGatewaySource() {
            Payment payment = capturedPayment();
            Refund pending = pendingRefund(payment.getId());

            when(refundRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
            when(refundRepository.compareAndSetPendingToFailed(pending.getId(), pending.getVersion()))
                .thenReturn(1);
            when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));

            helper.markRefundFailed(pending.getId());

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(outboxService).publish(
                eq("Payment"),
                eq(payment.getId().toString()),
                eq("PaymentRefundFailed"),
                payloadCaptor.capture());

            Map<String, Object> payload = payloadCaptor.getValue();
            assertThat(payload)
                .containsEntry("orderId", payment.getOrderId())
                .containsEntry("paymentId", payment.getId())
                .containsEntry("refundId", pending.getId())
                .containsEntry("amountCents", pending.getAmountCents())
                .containsEntry("currency", "INR")
                .containsEntry("reason", "damaged item")
                .containsEntry("failureSource", "gateway")
                .hasSize(7);
        }

        @Test
        @DisplayName("CAS success → audit log REFUND_GATEWAY_FAILED still emitted")
        void casSuccess_preservesAuditLog() {
            Payment payment = capturedPayment();
            Refund pending = pendingRefund(payment.getId());

            when(refundRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
            when(refundRepository.compareAndSetPendingToFailed(pending.getId(), pending.getVersion()))
                .thenReturn(1);
            when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));

            helper.markRefundFailed(pending.getId());

            verify(auditLogService).logSafely(
                any(),
                eq("REFUND_GATEWAY_FAILED"),
                eq("Refund"),
                eq(pending.getId().toString()),
                eq(Map.of("paymentId", payment.getId())));
        }

        @Test
        @DisplayName("CAS row-count 0 → no PaymentRefundFailed event published")
        void casConflict_noEventPublished() {
            Refund pending = pendingRefund(UUID.randomUUID());

            when(refundRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
            when(refundRepository.compareAndSetPendingToFailed(pending.getId(), pending.getVersion()))
                .thenReturn(0);

            helper.markRefundFailed(pending.getId());

            verifyNoInteractions(outboxService);
        }

        @Test
        @DisplayName("CAS success but payment not found → throws PaymentNotFoundException")
        void casSuccess_paymentNotFound_throws() {
            UUID paymentId = UUID.randomUUID();
            Refund pending = pendingRefund(paymentId);

            when(refundRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
            when(refundRepository.compareAndSetPendingToFailed(pending.getId(), pending.getVersion()))
                .thenReturn(1);
            when(paymentRepository.findById(paymentId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> helper.markRefundFailed(pending.getId()))
                .isInstanceOf(PaymentNotFoundException.class);

            verifyNoInteractions(outboxService);
        }
    }

    // --- resolveStaleRefundFailed: PaymentRefundFailed event ---

    @Nested
    @DisplayName("resolveStaleRefundFailed: PaymentRefundFailed event")
    class ResolveStaleRefundFailedEvent {

        @Test
        @DisplayName("CAS success → publishes PaymentRefundFailed with failureSource=recovery and exact payload shape")
        void casSuccess_publishesEventWithRecoverySource() {
            Payment payment = capturedPayment();
            Refund pending = pendingRefund(payment.getId());

            when(refundRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
            when(refundRepository.compareAndSetPendingToFailed(pending.getId(), pending.getVersion()))
                .thenReturn(1);
            when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));

            helper.resolveStaleRefundFailed(pending.getId(), "stale after 15 minutes");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(outboxService).publish(
                eq("Payment"),
                eq(payment.getId().toString()),
                eq("PaymentRefundFailed"),
                payloadCaptor.capture());

            Map<String, Object> payload = payloadCaptor.getValue();
            assertThat(payload)
                .containsEntry("orderId", payment.getOrderId())
                .containsEntry("paymentId", payment.getId())
                .containsEntry("refundId", pending.getId())
                .containsEntry("amountCents", pending.getAmountCents())
                .containsEntry("currency", "INR")
                .containsEntry("reason", "stale after 15 minutes")
                .containsEntry("failureSource", "recovery")
                .hasSize(7);
        }

        @Test
        @DisplayName("CAS success → audit log RECOVERY_REFUND_FAILED still emitted")
        void casSuccess_preservesAuditLog() {
            Payment payment = capturedPayment();
            Refund pending = pendingRefund(payment.getId());

            when(refundRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
            when(refundRepository.compareAndSetPendingToFailed(pending.getId(), pending.getVersion()))
                .thenReturn(1);
            when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));

            helper.resolveStaleRefundFailed(pending.getId(), "gateway timeout");

            verify(auditLogService).logSafely(
                any(),
                eq("RECOVERY_REFUND_FAILED"),
                eq("Refund"),
                eq(pending.getId().toString()),
                eq(Map.of("paymentId", payment.getId(), "reason", "gateway timeout")));
        }

        @Test
        @DisplayName("CAS row-count 0 → no PaymentRefundFailed event published")
        void casConflict_noEventPublished() {
            Refund pending = pendingRefund(UUID.randomUUID());

            when(refundRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
            when(refundRepository.compareAndSetPendingToFailed(pending.getId(), pending.getVersion()))
                .thenReturn(0);

            helper.resolveStaleRefundFailed(pending.getId(), "stale after 15 minutes");

            verifyNoInteractions(outboxService);
        }

        @Test
        @DisplayName("CAS success but payment not found → throws PaymentNotFoundException")
        void casSuccess_paymentNotFound_throws() {
            UUID paymentId = UUID.randomUUID();
            Refund pending = pendingRefund(paymentId);

            when(refundRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
            when(refundRepository.compareAndSetPendingToFailed(pending.getId(), pending.getVersion()))
                .thenReturn(1);
            when(paymentRepository.findById(paymentId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> helper.resolveStaleRefundFailed(pending.getId(), "stale"))
                .isInstanceOf(PaymentNotFoundException.class);

            verifyNoInteractions(outboxService);
        }
    }
}
