package com.instacommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.domain.model.Refund;
import com.instacommerce.payment.domain.model.RefundStatus;
import com.instacommerce.payment.dto.request.RefundRequest;
import com.instacommerce.payment.repository.PaymentRepository;
import com.instacommerce.payment.repository.RefundRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ConcurrentModificationException;
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
 * Unit tests for the CAS migration of {@link RefundTransactionHelper#completeRefund}.
 * Verifies that the primary synchronous refund completion path uses compare-and-set
 * semantics instead of plain {@code save()}, preventing silent overwrites after
 * a concurrent webhook completion.
 */
@ExtendWith(MockitoExtension.class)
class RefundTransactionHelperCasTest {

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
        r.setAmountCents(2500);
        r.setReason("customer request");
        r.setIdempotencyKey("idem-" + UUID.randomUUID());
        r.setStatus(RefundStatus.PENDING);
        r.setCreatedAt(Instant.now());
        r.setUpdatedAt(Instant.now());
        return r;
    }

    private Refund completedCopy(Refund original, String pspRefundId) {
        Refund r = new Refund();
        r.setId(original.getId());
        r.setPaymentId(original.getPaymentId());
        r.setAmountCents(original.getAmountCents());
        r.setReason(original.getReason());
        r.setIdempotencyKey(original.getIdempotencyKey());
        r.setPspRefundId(pspRefundId);
        r.setStatus(RefundStatus.COMPLETED);
        r.setVersion(original.getVersion() + 1);
        r.setCreatedAt(original.getCreatedAt());
        r.setUpdatedAt(Instant.now());
        return r;
    }

    private Refund failedCopy(Refund original) {
        Refund r = new Refund();
        r.setId(original.getId());
        r.setPaymentId(original.getPaymentId());
        r.setAmountCents(original.getAmountCents());
        r.setReason(original.getReason());
        r.setIdempotencyKey(original.getIdempotencyKey());
        r.setStatus(RefundStatus.FAILED);
        r.setVersion(original.getVersion() + 1);
        r.setCreatedAt(original.getCreatedAt());
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

    private RefundRequest refundRequest(long amountCents, String reason) {
        return new RefundRequest(amountCents, reason, "idem-key-" + UUID.randomUUID());
    }

    // --- completeRefund CAS tests ---

    @Nested
    @DisplayName("completeRefund: CAS-based mutation")
    class CompleteRefundCas {

        private static final String PSP_REFUND_ID = "psp-refund-abc123";

        @Test
        @DisplayName("CAS success → refund completed, payment updated, ledger/outbox/audit emitted once")
        void casSuccess_happyPath() {
            Payment payment = capturedPayment();
            Refund pending = pendingRefund(payment.getId());
            RefundRequest request = refundRequest(pending.getAmountCents(), pending.getReason());

            when(paymentRepository.findByIdForUpdate(payment.getId()))
                .thenReturn(Optional.of(payment));
            when(refundRepository.findById(pending.getId()))
                .thenReturn(Optional.of(pending));
            when(refundRepository.compareAndSetPendingToCompletedWithPspRefundId(
                pending.getId(), PSP_REFUND_ID, pending.getVersion()))
                .thenReturn(1);
            when(paymentRepository.save(payment)).thenReturn(payment);

            Refund result = helper.completeRefund(pending.getId(), payment.getId(), request, PSP_REFUND_ID);

            // Returned refund is marked COMPLETED in-memory
            assertThat(result.getStatus()).isEqualTo(RefundStatus.COMPLETED);
            assertThat(result.getPspRefundId()).isEqualTo(PSP_REFUND_ID);
            assertThat(result.getId()).isEqualTo(pending.getId());

            // Entity detached after CAS success to prevent dirty-check conflicts
            verify(entityManager).detach(pending);

            // Payment state updated correctly
            assertThat(payment.getRefundedCents()).isEqualTo(pending.getAmountCents());
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
            verify(paymentRepository).save(payment);

            // Ledger double-entry emitted exactly once
            verify(ledgerService).recordDoubleEntry(
                eq(payment.getId()),
                eq(request.amountCents()),
                eq("merchant_payable"),
                eq("customer_receivable"),
                eq("REFUND"),
                eq(pending.getId().toString()),
                eq("Refund"));

            // Outbox event emitted exactly once
            verify(outboxService).publish(
                eq("Payment"),
                eq(payment.getId().toString()),
                eq("PaymentRefunded"),
                any());

            // Audit log emitted exactly once
            verify(auditLogService).logSafely(
                any(),
                eq("REFUND_ISSUED"),
                eq("Refund"),
                eq(pending.getId().toString()),
                any());

            // No legacy refundRepository.save() call
            verify(refundRepository, never()).save(any(Refund.class));
        }

        @Test
        @DisplayName("CAS returns 0, re-read COMPLETED → idempotent return, no duplicate side effects")
        void casLost_rereadCompleted_idempotent() {
            Payment payment = capturedPayment();
            Refund pending = pendingRefund(payment.getId());
            Refund concurrentlyCompleted = completedCopy(pending, "psp-concurrent-winner");
            RefundRequest request = refundRequest(pending.getAmountCents(), pending.getReason());

            when(paymentRepository.findByIdForUpdate(payment.getId()))
                .thenReturn(Optional.of(payment));
            // First findById: initial read (PENDING), second: re-read after CAS=0 (COMPLETED)
            when(refundRepository.findById(pending.getId()))
                .thenReturn(Optional.of(pending))
                .thenReturn(Optional.of(concurrentlyCompleted));
            when(refundRepository.compareAndSetPendingToCompletedWithPspRefundId(
                pending.getId(), PSP_REFUND_ID, pending.getVersion()))
                .thenReturn(0);

            Refund result = helper.completeRefund(pending.getId(), payment.getId(), request, PSP_REFUND_ID);

            assertThat(result.getStatus()).isEqualTo(RefundStatus.COMPLETED);
            assertThat(result.getId()).isEqualTo(pending.getId());

            // No ledger, outbox, or audit — the concurrent writer owns those
            verifyNoInteractions(ledgerService, outboxService, auditLogService);

            // Entity detached before re-read to bypass L1 cache
            verify(entityManager).detach(pending);

            // Payment refundedCents must NOT be bumped
            assertThat(payment.getRefundedCents()).isEqualTo(0);

            // No save calls
            verify(refundRepository, never()).save(any(Refund.class));
            verify(paymentRepository, never()).save(any(Payment.class));
        }

        @Test
        @DisplayName("CAS returns 0, re-read FAILED → throws ConcurrentModificationException")
        void casLost_rereadFailed_throws() {
            Payment payment = capturedPayment();
            Refund pending = pendingRefund(payment.getId());
            Refund failedByOther = failedCopy(pending);
            RefundRequest request = refundRequest(pending.getAmountCents(), pending.getReason());

            when(paymentRepository.findByIdForUpdate(payment.getId()))
                .thenReturn(Optional.of(payment));
            when(refundRepository.findById(pending.getId()))
                .thenReturn(Optional.of(pending))
                .thenReturn(Optional.of(failedByOther));
            when(refundRepository.compareAndSetPendingToCompletedWithPspRefundId(
                pending.getId(), PSP_REFUND_ID, pending.getVersion()))
                .thenReturn(0);

            assertThatThrownBy(() ->
                helper.completeRefund(pending.getId(), payment.getId(), request, PSP_REFUND_ID))
                .isInstanceOf(ConcurrentModificationException.class)
                .hasMessageContaining("concurrently modified")
                .hasMessageContaining("FAILED");

            // No side effects
            verifyNoInteractions(ledgerService, outboxService, auditLogService);

            // Entity detached before re-read
            verify(entityManager).detach(pending);

            // Payment not modified
            assertThat(payment.getRefundedCents()).isEqualTo(0);
        }

        @Test
        @DisplayName("Already COMPLETED on initial read → early return without CAS attempt")
        void alreadyCompleted_earlyReturn() {
            Payment payment = capturedPayment();
            Refund alreadyDone = pendingRefund(payment.getId());
            alreadyDone.setStatus(RefundStatus.COMPLETED);
            alreadyDone.setPspRefundId("psp-existing");
            RefundRequest request = refundRequest(alreadyDone.getAmountCents(), alreadyDone.getReason());

            when(paymentRepository.findByIdForUpdate(payment.getId()))
                .thenReturn(Optional.of(payment));
            when(refundRepository.findById(alreadyDone.getId()))
                .thenReturn(Optional.of(alreadyDone));

            Refund result = helper.completeRefund(alreadyDone.getId(), payment.getId(), request, PSP_REFUND_ID);

            assertThat(result.getStatus()).isEqualTo(RefundStatus.COMPLETED);

            // No CAS attempt
            verify(refundRepository, never())
                .compareAndSetPendingToCompletedWithPspRefundId(any(), anyString(), anyLong());

            // No side effects
            verifyNoInteractions(ledgerService, outboxService, auditLogService);
            verify(entityManager, never()).detach(any());

            // Payment not modified
            assertThat(payment.getRefundedCents()).isEqualTo(0);
        }
    }
}
