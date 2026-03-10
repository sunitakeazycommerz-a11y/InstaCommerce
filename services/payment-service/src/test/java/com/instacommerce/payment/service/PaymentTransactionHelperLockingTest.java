package com.instacommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.repository.PaymentRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies that every mutating method in {@link PaymentTransactionHelper}
 * acquires a pessimistic write lock via {@code findByIdForUpdate} and never
 * falls back to the unlocked {@code findById} path.
 *
 * <p>These tests do not exercise JPA locking semantics; they only prove
 * the helper routes through the correct repository method.
 */
@ExtendWith(MockitoExtension.class)
class PaymentTransactionHelperLockingTest {

    @Mock PaymentRepository paymentRepository;
    @Mock LedgerService ledgerService;
    @Mock OutboxService outboxService;
    @Mock AuditLogService auditLogService;

    private PaymentTransactionHelper helper;

    @BeforeEach
    void setUp() {
        helper = new PaymentTransactionHelper(
            paymentRepository, ledgerService, outboxService, auditLogService);
    }

    // --- Factory helpers ---

    private Payment paymentInStatus(PaymentStatus status) {
        Payment p = new Payment();
        p.setId(UUID.randomUUID());
        p.setOrderId(UUID.randomUUID());
        p.setAmountCents(5_000);
        p.setCapturedCents(0);
        p.setRefundedCents(0);
        p.setCurrency("INR");
        p.setStatus(status);
        p.setPspReference("psp-ref-" + UUID.randomUUID());
        return p;
    }

    private void stubForUpdate(Payment payment) {
        when(paymentRepository.findByIdForUpdate(payment.getId()))
            .thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class)))
            .thenAnswer(inv -> inv.getArgument(0));
    }

    private void verifyUsedForUpdateOnly(UUID paymentId) {
        verify(paymentRepository).findByIdForUpdate(paymentId);
        verify(paymentRepository, never()).findById(any(UUID.class));
    }

    // =====================================================================
    // Completion methods
    // =====================================================================

    @Nested
    @DisplayName("Completion methods use findByIdForUpdate")
    class CompletionMethods {

        @Test
        @DisplayName("completeAuthorization locks via findByIdForUpdate")
        void completeAuthorization_usesForUpdate() {
            Payment payment = paymentInStatus(PaymentStatus.AUTHORIZE_PENDING);
            stubForUpdate(payment);

            Payment result = helper.completeAuthorization(payment.getId(), "psp-123");

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
            verifyUsedForUpdateOnly(payment.getId());
        }

        @Test
        @DisplayName("completeCaptured locks via findByIdForUpdate")
        void completeCaptured_usesForUpdate() {
            Payment payment = paymentInStatus(PaymentStatus.CAPTURE_PENDING);
            stubForUpdate(payment);

            Payment result = helper.completeCaptured(payment.getId(), 5_000);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            verifyUsedForUpdateOnly(payment.getId());
        }

        @Test
        @DisplayName("completeVoided locks via findByIdForUpdate")
        void completeVoided_usesForUpdate() {
            Payment payment = paymentInStatus(PaymentStatus.VOID_PENDING);
            stubForUpdate(payment);

            Payment result = helper.completeVoided(payment.getId());

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.VOIDED);
            verifyUsedForUpdateOnly(payment.getId());
        }
    }

    // =====================================================================
    // Pending-transition methods
    // =====================================================================

    @Nested
    @DisplayName("Pending-transition methods use findByIdForUpdate")
    class PendingTransitionMethods {

        @Test
        @DisplayName("saveCapturePending locks via findByIdForUpdate")
        void saveCapturePending_usesForUpdate() {
            Payment payment = paymentInStatus(PaymentStatus.AUTHORIZED);
            stubForUpdate(payment);

            Payment result = helper.saveCapturePending(payment.getId());

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.CAPTURE_PENDING);
            verifyUsedForUpdateOnly(payment.getId());
        }

        @Test
        @DisplayName("saveVoidPending locks via findByIdForUpdate")
        void saveVoidPending_usesForUpdate() {
            Payment payment = paymentInStatus(PaymentStatus.AUTHORIZED);
            stubForUpdate(payment);

            Payment result = helper.saveVoidPending(payment.getId());

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.VOID_PENDING);
            verifyUsedForUpdateOnly(payment.getId());
        }
    }

    // =====================================================================
    // Revert methods
    // =====================================================================

    @Nested
    @DisplayName("Revert methods use findByIdForUpdate")
    class RevertMethods {

        @Test
        @DisplayName("markAuthorizationFailed locks via findByIdForUpdate")
        void markAuthorizationFailed_usesForUpdate() {
            Payment payment = paymentInStatus(PaymentStatus.AUTHORIZE_PENDING);
            stubForUpdate(payment);

            helper.markAuthorizationFailed(payment.getId());

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            verifyUsedForUpdateOnly(payment.getId());
        }

        @Test
        @DisplayName("revertToAuthorized locks via findByIdForUpdate")
        void revertToAuthorized_usesForUpdate() {
            Payment payment = paymentInStatus(PaymentStatus.CAPTURE_PENDING);
            stubForUpdate(payment);

            helper.revertToAuthorized(payment.getId());

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
            verifyUsedForUpdateOnly(payment.getId());
        }

        @Test
        @DisplayName("revertVoidToAuthorized locks via findByIdForUpdate")
        void revertVoidToAuthorized_usesForUpdate() {
            Payment payment = paymentInStatus(PaymentStatus.VOID_PENDING);
            stubForUpdate(payment);

            helper.revertVoidToAuthorized(payment.getId());

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
            verifyUsedForUpdateOnly(payment.getId());
        }
    }

    // =====================================================================
    // Reconciliation
    // =====================================================================

    @Nested
    @DisplayName("Reconciliation methods use findByIdForUpdate")
    class ReconciliationMethods {

        @Test
        @DisplayName("reconcileDirectToCaptured locks via findByIdForUpdate")
        void reconcileDirectToCaptured_usesForUpdate() {
            Payment payment = paymentInStatus(PaymentStatus.AUTHORIZE_PENDING);
            stubForUpdate(payment);

            Payment result = helper.reconcileDirectToCaptured(
                payment.getId(), "psp-reconcile-ref", 5_000);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(result.getCapturedCents()).isEqualTo(5_000);
            verifyUsedForUpdateOnly(payment.getId());
        }
    }

    // =====================================================================
    // Stale-recovery resolution helpers
    // =====================================================================

    @Nested
    @DisplayName("Stale-recovery resolution helpers use findByIdForUpdate")
    class StaleRecoveryMethods {

        @Test
        @DisplayName("resolveStaleAuthorizationFailed locks via findByIdForUpdate")
        void resolveStaleAuthorizationFailed_usesForUpdate() {
            Payment payment = paymentInStatus(PaymentStatus.AUTHORIZE_PENDING);
            stubForUpdate(payment);

            helper.resolveStaleAuthorizationFailed(payment.getId(), "PSP timeout");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            verifyUsedForUpdateOnly(payment.getId());
        }

        @Test
        @DisplayName("resolveStaleCaptureFailed locks via findByIdForUpdate")
        void resolveStaleCaptureFailed_usesForUpdate() {
            Payment payment = paymentInStatus(PaymentStatus.CAPTURE_PENDING);
            stubForUpdate(payment);

            helper.resolveStaleCaptureFailed(payment.getId(), "PSP timeout");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
            verifyUsedForUpdateOnly(payment.getId());
        }

        @Test
        @DisplayName("resolveStaleVoidFailed locks via findByIdForUpdate")
        void resolveStaleVoidFailed_usesForUpdate() {
            Payment payment = paymentInStatus(PaymentStatus.VOID_PENDING);
            stubForUpdate(payment);

            helper.resolveStaleVoidFailed(payment.getId(), "PSP timeout");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
            verifyUsedForUpdateOnly(payment.getId());
        }
    }

    // =====================================================================
    // Negative guard: findById is never invoked across all mutating paths
    // =====================================================================

    @Nested
    @DisplayName("findById is never called by any mutating helper method")
    class FindByIdNeverUsed {

        @Test
        @DisplayName("full sweep: exercising every mutating method produces zero findById calls")
        void noFindByIdAcrossAllMutatingMethods() {
            // Completion
            Payment authPending = paymentInStatus(PaymentStatus.AUTHORIZE_PENDING);
            stubForUpdate(authPending);
            helper.completeAuthorization(authPending.getId(), "psp-1");

            Payment capPending = paymentInStatus(PaymentStatus.CAPTURE_PENDING);
            stubForUpdate(capPending);
            helper.completeCaptured(capPending.getId(), 1_000);

            Payment voidPending = paymentInStatus(PaymentStatus.VOID_PENDING);
            stubForUpdate(voidPending);
            helper.completeVoided(voidPending.getId());

            // Pending transitions
            Payment authorized1 = paymentInStatus(PaymentStatus.AUTHORIZED);
            stubForUpdate(authorized1);
            helper.saveCapturePending(authorized1.getId());

            Payment authorized2 = paymentInStatus(PaymentStatus.AUTHORIZED);
            stubForUpdate(authorized2);
            helper.saveVoidPending(authorized2.getId());

            // Reverts
            Payment authPending2 = paymentInStatus(PaymentStatus.AUTHORIZE_PENDING);
            stubForUpdate(authPending2);
            helper.markAuthorizationFailed(authPending2.getId());

            Payment capPending2 = paymentInStatus(PaymentStatus.CAPTURE_PENDING);
            stubForUpdate(capPending2);
            helper.revertToAuthorized(capPending2.getId());

            Payment voidPending2 = paymentInStatus(PaymentStatus.VOID_PENDING);
            stubForUpdate(voidPending2);
            helper.revertVoidToAuthorized(voidPending2.getId());

            // Reconciliation
            Payment authPending3 = paymentInStatus(PaymentStatus.AUTHORIZE_PENDING);
            stubForUpdate(authPending3);
            helper.reconcileDirectToCaptured(authPending3.getId(), "psp-r", 2_000);

            // Stale-recovery
            Payment authPending4 = paymentInStatus(PaymentStatus.AUTHORIZE_PENDING);
            stubForUpdate(authPending4);
            helper.resolveStaleAuthorizationFailed(authPending4.getId(), "timeout");

            Payment capPending3 = paymentInStatus(PaymentStatus.CAPTURE_PENDING);
            stubForUpdate(capPending3);
            helper.resolveStaleCaptureFailed(capPending3.getId(), "timeout");

            Payment voidPending3 = paymentInStatus(PaymentStatus.VOID_PENDING);
            stubForUpdate(voidPending3);
            helper.resolveStaleVoidFailed(voidPending3.getId(), "timeout");

            // The critical assertion: findById was never invoked
            verify(paymentRepository, never()).findById(any(UUID.class));
        }
    }
}
