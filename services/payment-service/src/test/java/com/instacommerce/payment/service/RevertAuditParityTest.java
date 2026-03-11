package com.instacommerce.payment.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.domain.model.Refund;
import com.instacommerce.payment.domain.model.RefundStatus;
import com.instacommerce.payment.repository.LedgerEntryRepository;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Verifies audit-log and outbox parity for the revert/failure paths that
 * previously lacked the side effects their recovery-path equivalents emit.
 *
 * <ul>
 *   <li>{@link PaymentTransactionHelper#revertToAuthorized} → outbox + audit</li>
 *   <li>{@link PaymentTransactionHelper#revertVoidToAuthorized} → outbox + audit</li>
 *   <li>{@link RefundTransactionHelper#markRefundFailed} → audit after CAS success</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class RevertAuditParityTest {

    // --- PaymentTransactionHelper mocks ---
    @Mock PaymentRepository paymentRepository;
    @Mock LedgerService ledgerService;
    @Mock LedgerEntryRepository ledgerEntryRepository;
    @Mock OutboxService outboxService;
    @Mock AuditLogService auditLogService;

    // --- RefundTransactionHelper mocks ---
    @Mock RefundRepository refundRepository;
    @Mock EntityManager entityManager;

    private PaymentTransactionHelper paymentHelper;
    private RefundTransactionHelper refundHelper;

    @BeforeEach
    void setUp() {
        paymentHelper = new PaymentTransactionHelper(
            paymentRepository, ledgerService, ledgerEntryRepository, outboxService, auditLogService);
        refundHelper = new RefundTransactionHelper(
            refundRepository, paymentRepository, ledgerService, outboxService, auditLogService);
        ReflectionTestUtils.setField(refundHelper, "entityManager", entityManager);
    }

    // --- Factory helpers ---

    private Payment paymentInStatus(PaymentStatus status) {
        Payment p = new Payment();
        p.setId(UUID.randomUUID());
        p.setOrderId(UUID.randomUUID());
        p.setAmountCents(10_000);
        p.setCapturedCents(0);
        p.setRefundedCents(0);
        p.setCurrency("INR");
        p.setStatus(status);
        p.setPspReference("psp-ref-" + UUID.randomUUID());
        return p;
    }

    private Refund pendingRefund(UUID paymentId) {
        Refund r = new Refund();
        r.setId(UUID.randomUUID());
        r.setPaymentId(paymentId);
        r.setAmountCents(2500);
        r.setReason("customer request");
        r.setIdempotencyKey("idem-" + UUID.randomUUID());
        r.setStatus(RefundStatus.PENDING);
        r.setCreatedAt(Instant.now());
        r.setUpdatedAt(Instant.now());
        return r;
    }

    // =====================================================================
    // revertToAuthorized
    // =====================================================================

    @Nested
    @DisplayName("revertToAuthorized: outbox + audit parity")
    class RevertToAuthorized {

        @Test
        @DisplayName("publishes PaymentCaptureReverted outbox event")
        void revertToAuthorized_publishesOutboxEvent() {
            Payment payment = paymentInStatus(PaymentStatus.CAPTURE_PENDING);
            when(paymentRepository.findByIdForUpdate(payment.getId()))
                .thenReturn(Optional.of(payment));
            when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            paymentHelper.revertToAuthorized(payment.getId());

            verify(outboxService).publish(
                eq("Payment"),
                eq(payment.getId().toString()),
                eq("PaymentCaptureReverted"),
                any(Map.class));
        }

        @Test
        @DisplayName("records CAPTURE_REVERTED audit log")
        void revertToAuthorized_recordsAuditLog() {
            Payment payment = paymentInStatus(PaymentStatus.CAPTURE_PENDING);
            when(paymentRepository.findByIdForUpdate(payment.getId()))
                .thenReturn(Optional.of(payment));
            when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            paymentHelper.revertToAuthorized(payment.getId());

            verify(auditLogService).logSafely(
                isNull(),
                eq("CAPTURE_REVERTED"),
                eq("Payment"),
                eq(payment.getId().toString()),
                any(Map.class));
        }

        @Test
        @DisplayName("non-CAPTURE_PENDING status → no outbox or audit")
        void revertToAuthorized_nonCapturePending_noOutboxOrAudit() {
            Payment payment = paymentInStatus(PaymentStatus.AUTHORIZED);
            when(paymentRepository.findByIdForUpdate(payment.getId()))
                .thenReturn(Optional.of(payment));

            paymentHelper.revertToAuthorized(payment.getId());

            verify(outboxService, never()).publish(any(), any(), any(), any(Map.class));
            verify(auditLogService, never()).logSafely(any(), any(), any(), any(), any(Map.class));
        }
    }

    // =====================================================================
    // revertVoidToAuthorized
    // =====================================================================

    @Nested
    @DisplayName("revertVoidToAuthorized: outbox + audit parity")
    class RevertVoidToAuthorized {

        @Test
        @DisplayName("publishes PaymentVoidReverted outbox event")
        void revertVoidToAuthorized_publishesOutboxEvent() {
            Payment payment = paymentInStatus(PaymentStatus.VOID_PENDING);
            when(paymentRepository.findByIdForUpdate(payment.getId()))
                .thenReturn(Optional.of(payment));
            when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            paymentHelper.revertVoidToAuthorized(payment.getId());

            verify(outboxService).publish(
                eq("Payment"),
                eq(payment.getId().toString()),
                eq("PaymentVoidReverted"),
                any(Map.class));
        }

        @Test
        @DisplayName("records VOID_REVERTED audit log")
        void revertVoidToAuthorized_recordsAuditLog() {
            Payment payment = paymentInStatus(PaymentStatus.VOID_PENDING);
            when(paymentRepository.findByIdForUpdate(payment.getId()))
                .thenReturn(Optional.of(payment));
            when(paymentRepository.save(any(Payment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            paymentHelper.revertVoidToAuthorized(payment.getId());

            verify(auditLogService).logSafely(
                isNull(),
                eq("VOID_REVERTED"),
                eq("Payment"),
                eq(payment.getId().toString()),
                any(Map.class));
        }

        @Test
        @DisplayName("non-VOID_PENDING status → no outbox or audit")
        void revertVoidToAuthorized_nonVoidPending_noOutboxOrAudit() {
            Payment payment = paymentInStatus(PaymentStatus.AUTHORIZED);
            when(paymentRepository.findByIdForUpdate(payment.getId()))
                .thenReturn(Optional.of(payment));

            paymentHelper.revertVoidToAuthorized(payment.getId());

            verify(outboxService, never()).publish(any(), any(), any(), any(Map.class));
            verify(auditLogService, never()).logSafely(any(), any(), any(), any(), any(Map.class));
        }
    }

    // =====================================================================
    // markRefundFailed
    // =====================================================================

    @Nested
    @DisplayName("markRefundFailed: audit parity")
    class MarkRefundFailed {

        @Test
        @DisplayName("CAS success → records REFUND_GATEWAY_FAILED audit log")
        void markRefundFailed_recordsAuditLog() {
            Payment payment = paymentInStatus(PaymentStatus.CAPTURED);
            Refund refund = pendingRefund(payment.getId());
            when(refundRepository.findById(refund.getId()))
                .thenReturn(Optional.of(refund));
            when(refundRepository.compareAndSetPendingToFailed(refund.getId(), refund.getVersion()))
                .thenReturn(1);
            when(paymentRepository.findById(payment.getId()))
                .thenReturn(Optional.of(payment));

            refundHelper.markRefundFailed(refund.getId());

            verify(auditLogService).logSafely(
                isNull(),
                eq("REFUND_GATEWAY_FAILED"),
                eq("Refund"),
                eq(refund.getId().toString()),
                any(Map.class));
        }

        @Test
        @DisplayName("CAS returns 0 → no audit log")
        void markRefundFailed_casFailure_noAuditLog() {
            UUID paymentId = UUID.randomUUID();
            Refund refund = pendingRefund(paymentId);
            when(refundRepository.findById(refund.getId()))
                .thenReturn(Optional.of(refund));
            when(refundRepository.compareAndSetPendingToFailed(refund.getId(), refund.getVersion()))
                .thenReturn(0);

            refundHelper.markRefundFailed(refund.getId());

            verify(auditLogService, never()).logSafely(any(), any(), any(), any(), any(Map.class));
        }

        @Test
        @DisplayName("non-PENDING refund → no audit log")
        void markRefundFailed_nonPending_noAuditLog() {
            UUID paymentId = UUID.randomUUID();
            Refund refund = pendingRefund(paymentId);
            refund.setStatus(RefundStatus.COMPLETED);
            when(refundRepository.findById(refund.getId()))
                .thenReturn(Optional.of(refund));

            refundHelper.markRefundFailed(refund.getId());

            verify(auditLogService, never()).logSafely(any(), any(), any(), any(), any(Map.class));
        }
    }
}
