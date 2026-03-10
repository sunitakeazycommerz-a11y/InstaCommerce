package com.instacommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.domain.model.Refund;
import com.instacommerce.payment.domain.model.RefundStatus;
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
 * Unit tests proving that CAS (compare-and-set) row-count conflicts in
 * {@link RefundTransactionHelper} are handled safely: no silent data corruption,
 * no double-counting, and concurrent writers are allowed to win.
 */
@ExtendWith(MockitoExtension.class)
class RefundTransactionHelperOccTest {

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
        r.setReason("test");
        r.setIdempotencyKey("idem-" + UUID.randomUUID());
        r.setStatus(RefundStatus.PENDING);
        r.setCreatedAt(Instant.now());
        r.setUpdatedAt(Instant.now());
        return r;
    }

    private Refund completedCopy(Refund original) {
        Refund r = new Refund();
        r.setId(original.getId());
        r.setPaymentId(original.getPaymentId());
        r.setAmountCents(original.getAmountCents());
        r.setReason(original.getReason());
        r.setIdempotencyKey(original.getIdempotencyKey());
        r.setPspRefundId("psp-concurrent-winner");
        r.setStatus(RefundStatus.COMPLETED);
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

    // --- markRefundFailed CAS ---

    @Nested
    @DisplayName("markRefundFailed: CAS conflict handling")
    class MarkRefundFailedOcc {

        @Test
        @DisplayName("CAS row-count 0 → concurrent writer wins, no side effects")
        void casConflict_concurrentWriterWins() {
            UUID refundId = UUID.randomUUID();
            Refund pending = pendingRefund(UUID.randomUUID());
            pending.setId(refundId);

            when(refundRepository.findById(refundId)).thenReturn(Optional.of(pending));
            when(refundRepository.compareAndSetPendingToFailed(refundId, pending.getVersion()))
                .thenReturn(0);

            // Must not propagate any exception
            helper.markRefundFailed(refundId);

            // No ledger, outbox, or audit side effects
            verifyNoInteractions(ledgerService, outboxService, auditLogService);
        }

        @Test
        @DisplayName("Non-PENDING refund is skipped before CAS attempt")
        void nonPendingSkipped() {
            UUID refundId = UUID.randomUUID();
            Refund completed = pendingRefund(UUID.randomUUID());
            completed.setId(refundId);
            completed.setStatus(RefundStatus.COMPLETED);

            when(refundRepository.findById(refundId)).thenReturn(Optional.of(completed));

            helper.markRefundFailed(refundId);

            verify(refundRepository, never()).compareAndSetPendingToFailed(any(), anyLong());
        }
    }

    // --- resolveStaleRefundFailed CAS ---

    @Nested
    @DisplayName("resolveStaleRefundFailed: CAS conflict handling")
    class ResolveStaleRefundFailedOcc {

        @Test
        @DisplayName("CAS row-count 0 → audit log NOT written, concurrent writer wins")
        void casConflict_noAuditLog() {
            UUID refundId = UUID.randomUUID();
            Refund pending = pendingRefund(UUID.randomUUID());
            pending.setId(refundId);

            when(refundRepository.findById(refundId)).thenReturn(Optional.of(pending));
            when(refundRepository.compareAndSetPendingToFailed(refundId, pending.getVersion()))
                .thenReturn(0);

            helper.resolveStaleRefundFailed(refundId, "stale after 15 minutes");

            // Critically: audit log must NOT be written after CAS conflict
            verifyNoInteractions(auditLogService);
            verifyNoInteractions(ledgerService, outboxService);
        }

        @Test
        @DisplayName("CAS row-count 1 → audit log written on successful recovery")
        void casSuccess_auditsRecovery() {
            UUID paymentId = UUID.randomUUID();
            UUID refundId = UUID.randomUUID();
            Refund pending = pendingRefund(paymentId);
            pending.setId(refundId);

            when(refundRepository.findById(refundId)).thenReturn(Optional.of(pending));
            when(refundRepository.compareAndSetPendingToFailed(refundId, pending.getVersion()))
                .thenReturn(1);

            helper.resolveStaleRefundFailed(refundId, "gateway timeout");

            verify(auditLogService).log(
                any(), // userId
                org.mockito.ArgumentMatchers.eq("RECOVERY_REFUND_FAILED"),
                org.mockito.ArgumentMatchers.eq("Refund"),
                org.mockito.ArgumentMatchers.eq(refundId.toString()),
                org.mockito.ArgumentMatchers.eq(Map.of("paymentId", paymentId, "reason", "gateway timeout")));
        }
    }

    // --- completeStaleRefund CAS ---

    @Nested
    @DisplayName("completeStaleRefund: CAS conflict handling")
    class CompleteStaleRefundOcc {

        @Test
        @DisplayName("CAS row-count 0 + re-read COMPLETED → idempotent return, no ledger/outbox duplication")
        void casThenRereadCompleted_idempotent() {
            Payment payment = capturedPayment();
            Refund pending = pendingRefund(payment.getId());
            Refund concurrentlyCompleted = completedCopy(pending);

            // Three findById calls: (1) get paymentId, (2) re-read after lock, (3) after CAS=0
            when(refundRepository.findById(pending.getId()))
                .thenReturn(Optional.of(pending))         // 1st: discover paymentId
                .thenReturn(Optional.of(pending))          // 2nd: re-read after lock (still PENDING)
                .thenReturn(Optional.of(concurrentlyCompleted)); // 3rd: CAS-lost re-read

            when(paymentRepository.findByIdForUpdate(payment.getId()))
                .thenReturn(Optional.of(payment));

            when(refundRepository.compareAndSetPendingToCompleted(pending.getId(), pending.getVersion()))
                .thenReturn(0);

            Refund result = helper.completeStaleRefund(pending.getId());

            assertThat(result.getStatus()).isEqualTo(RefundStatus.COMPLETED);
            assertThat(result.getId()).isEqualTo(pending.getId());

            // No ledger, outbox, or audit written — the concurrent writer owns those
            verifyNoInteractions(ledgerService, outboxService, auditLogService);

            // EntityManager.detach called on initial read AND after CAS=0
            verify(entityManager, org.mockito.Mockito.times(2)).detach(pending);

            // Payment refundedCents must NOT be bumped (the concurrent writer already did that)
            assertThat(payment.getRefundedCents()).isEqualTo(0);
        }

        @Test
        @DisplayName("CAS row-count 0 + re-read FAILED → throws for manual review")
        void casThenRereadFailed_throws() {
            Payment payment = capturedPayment();
            Refund pending = pendingRefund(payment.getId());

            Refund failedByOther = completedCopy(pending);
            failedByOther.setStatus(RefundStatus.FAILED);

            when(refundRepository.findById(pending.getId()))
                .thenReturn(Optional.of(pending))
                .thenReturn(Optional.of(pending))
                .thenReturn(Optional.of(failedByOther));

            when(paymentRepository.findByIdForUpdate(payment.getId()))
                .thenReturn(Optional.of(payment));

            when(refundRepository.compareAndSetPendingToCompleted(pending.getId(), pending.getVersion()))
                .thenReturn(0);

            assertThatThrownBy(() -> helper.completeStaleRefund(pending.getId()))
                .isInstanceOf(IllegalStateException.class);

            verifyNoInteractions(ledgerService, outboxService, auditLogService);
            verify(entityManager, org.mockito.Mockito.times(2)).detach(pending);
            assertThat(payment.getRefundedCents()).isEqualTo(0);
        }

        @Test
        @DisplayName("CAS row-count 0 + re-read throws → exception propagates for manual review")
        void casThenRereadFails_propagates() {
            Payment payment = capturedPayment();
            Refund pending = pendingRefund(payment.getId());

            when(refundRepository.findById(pending.getId()))
                .thenReturn(Optional.of(pending))
                .thenReturn(Optional.of(pending))
                .thenThrow(new RuntimeException("Hibernate session invalidated"));

            when(paymentRepository.findByIdForUpdate(payment.getId()))
                .thenReturn(Optional.of(payment));

            when(refundRepository.compareAndSetPendingToCompleted(pending.getId(), pending.getVersion()))
                .thenReturn(0);

            assertThatThrownBy(() -> helper.completeStaleRefund(pending.getId()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Hibernate session invalidated");

            verifyNoInteractions(ledgerService, outboxService, auditLogService);
            verify(entityManager, org.mockito.Mockito.times(2)).detach(pending);
        }

        @Test
        @DisplayName("CAS row-count 1 → refund completed, payment updated, ledger/outbox/audit emitted once")
        void casSuccess_happyPath() {
            Payment payment = capturedPayment();
            Refund pending = pendingRefund(payment.getId());

            // findById: (1) discover paymentId, (2) re-read after lock → still PENDING
            when(refundRepository.findById(pending.getId()))
                .thenReturn(Optional.of(pending))
                .thenReturn(Optional.of(pending));

            when(paymentRepository.findByIdForUpdate(payment.getId()))
                .thenReturn(Optional.of(payment));

            when(refundRepository.compareAndSetPendingToCompleted(pending.getId(), pending.getVersion()))
                .thenReturn(1);

            when(paymentRepository.save(payment)).thenReturn(payment);

            Refund result = helper.completeStaleRefund(pending.getId());

            // --- returned refund is marked COMPLETED in-memory ---
            assertThat(result.getStatus()).isEqualTo(RefundStatus.COMPLETED);
            assertThat(result.getId()).isEqualTo(pending.getId());

            // --- refund entity detached twice: initial read + after CAS success ---
            verify(entityManager, times(2)).detach(pending);

            // --- payment state updated correctly ---
            assertThat(payment.getRefundedCents()).isEqualTo(pending.getAmountCents());
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
            verify(paymentRepository).save(payment);

            // --- ledger double-entry emitted exactly once ---
            verify(ledgerService).recordDoubleEntry(
                org.mockito.ArgumentMatchers.eq(payment.getId()),
                org.mockito.ArgumentMatchers.eq((long) pending.getAmountCents()),
                org.mockito.ArgumentMatchers.eq("merchant_payable"),
                org.mockito.ArgumentMatchers.eq("customer_receivable"),
                org.mockito.ArgumentMatchers.eq("REFUND"),
                org.mockito.ArgumentMatchers.eq(pending.getId().toString()),
                org.mockito.ArgumentMatchers.eq("Refund"));

            // --- outbox event emitted exactly once with PaymentRefunded ---
            verify(outboxService).publish(
                org.mockito.ArgumentMatchers.eq("Payment"),
                org.mockito.ArgumentMatchers.eq(payment.getId().toString()),
                org.mockito.ArgumentMatchers.eq("PaymentRefunded"),
                any());

            // --- audit log emitted exactly once for recovery completion ---
            verify(auditLogService).log(
                any(),
                org.mockito.ArgumentMatchers.eq("RECOVERY_REFUND_COMPLETED"),
                org.mockito.ArgumentMatchers.eq("Refund"),
                org.mockito.ArgumentMatchers.eq(pending.getId().toString()),
                org.mockito.ArgumentMatchers.eq(Map.of(
                    "paymentId", payment.getId(),
                    "amountCents", pending.getAmountCents())));

            // --- no legacy refundRepository.save() call (CAS did the transition) ---
            verify(refundRepository, never()).save(any(Refund.class));
        }

        @Test
        @DisplayName("Already COMPLETED on re-read → early return, no CAS attempt")
        void alreadyCompleted_earlyReturn() {
            Payment payment = capturedPayment();
            Refund pending = pendingRefund(payment.getId());
            Refund completed = completedCopy(pending);

            when(refundRepository.findById(pending.getId()))
                .thenReturn(Optional.of(pending))          // 1st: discover paymentId
                .thenReturn(Optional.of(completed));        // 2nd: re-read shows already COMPLETED

            when(paymentRepository.findByIdForUpdate(payment.getId()))
                .thenReturn(Optional.of(payment));

            Refund result = helper.completeStaleRefund(pending.getId());

            assertThat(result.getStatus()).isEqualTo(RefundStatus.COMPLETED);
            verify(refundRepository, never()).compareAndSetPendingToCompleted(any(), anyLong());
            verify(entityManager).detach(pending);
            verifyNoInteractions(ledgerService, outboxService, auditLogService);
        }
    }
}
