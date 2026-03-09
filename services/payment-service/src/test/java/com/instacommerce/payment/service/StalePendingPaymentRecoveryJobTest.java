package com.instacommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.gateway.GatewayStatusResult;
import com.instacommerce.payment.gateway.GatewayStatusResult.PspPaymentState;
import com.instacommerce.payment.gateway.PaymentGateway;
import com.instacommerce.payment.repository.PaymentRepository;
import com.instacommerce.payment.service.StalePendingPaymentRecoveryJob.RecoveryOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StalePendingPaymentRecoveryJobTest {

    @Mock PaymentRepository paymentRepository;
    @Mock PaymentGateway paymentGateway;
    @Mock PaymentTransactionHelper txHelper;

    SimpleMeterRegistry meterRegistry;
    StalePendingPaymentRecoveryJob job;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        job = new StalePendingPaymentRecoveryJob(
            paymentRepository, paymentGateway, txHelper, meterRegistry, 30, 50);
    }

    // --- Helpers ---

    private Payment payment(PaymentStatus status, String pspRef) {
        Payment p = new Payment();
        p.setId(UUID.randomUUID());
        p.setOrderId(UUID.randomUUID());
        p.setAmountCents(10000);
        p.setCapturedCents(0);
        p.setRefundedCents(0);
        p.setCurrency("INR");
        p.setStatus(status);
        p.setPspReference(pspRef);
        p.setIdempotencyKey("key-" + UUID.randomUUID());
        p.setCreatedAt(Instant.now().minusSeconds(3600));
        p.setUpdatedAt(Instant.now().minusSeconds(3600));
        return p;
    }

    private GatewayStatusResult pspResult(PspPaymentState state) {
        return GatewayStatusResult.of(state, state.name().toLowerCase(), 0L);
    }

    private GatewayStatusResult pspResult(PspPaymentState state, long amountCaptured) {
        return GatewayStatusResult.of(state, state.name().toLowerCase(), amountCaptured);
    }

    // --- AUTHORIZE_PENDING ---

    @Nested
    @DisplayName("AUTHORIZE_PENDING recovery")
    class AuthorizePending {

        @Test
        @DisplayName("No PSP reference → marks failed")
        void noPspReference_marksFailed() {
            Payment p = payment(PaymentStatus.AUTHORIZE_PENDING, null);

            RecoveryOutcome outcome = job.recoverPayment(p);

            assertThat(outcome).isEqualTo(RecoveryOutcome.MARKED_FAILED);
            verify(txHelper).resolveStaleAuthorizationFailed(
                eq(p.getId()), eq("no_psp_reference_after_threshold"));
            verifyNoInteractions(paymentGateway);
        }

        @Test
        @DisplayName("Blank PSP reference → marks failed")
        void blankPspReference_marksFailed() {
            Payment p = payment(PaymentStatus.AUTHORIZE_PENDING, "  ");

            RecoveryOutcome outcome = job.recoverPayment(p);

            assertThat(outcome).isEqualTo(RecoveryOutcome.MARKED_FAILED);
            verify(txHelper).resolveStaleAuthorizationFailed(
                eq(p.getId()), eq("no_psp_reference_after_threshold"));
        }

        @Test
        @DisplayName("PSP says authorized → completes authorization")
        void pspAuthorized_completesAuth() {
            Payment p = payment(PaymentStatus.AUTHORIZE_PENDING, "pi_test_123");
            when(paymentGateway.getStatus("pi_test_123"))
                .thenReturn(pspResult(PspPaymentState.REQUIRES_CAPTURE));

            RecoveryOutcome outcome = job.recoverPayment(p);

            assertThat(outcome).isEqualTo(RecoveryOutcome.COMPLETED_FORWARD);
            verify(txHelper).completeAuthorization(p.getId(), "pi_test_123");
        }

        @Test
        @DisplayName("PSP says captured → reconciles directly to CAPTURED")
        void pspCaptured_reconcilesDirectToCaptured() {
            Payment p = payment(PaymentStatus.AUTHORIZE_PENDING, "pi_test_123");
            when(paymentGateway.getStatus("pi_test_123"))
                .thenReturn(pspResult(PspPaymentState.SUCCEEDED, 10000L));

            RecoveryOutcome outcome = job.recoverPayment(p);

            assertThat(outcome).isEqualTo(RecoveryOutcome.COMPLETED_FORWARD);
            verify(txHelper).reconcileDirectToCaptured(p.getId(), "pi_test_123", 10000L);
            verify(txHelper, never()).completeAuthorization(any(), any());
            verify(txHelper, never()).completeCaptured(any(), anyLong());
        }

        @Test
        @DisplayName("PSP says captured with no amount → reconciles using payment amountCents")
        void pspCapturedNoAmount_reconcilesUsingPaymentAmount() {
            Payment p = payment(PaymentStatus.AUTHORIZE_PENDING, "pi_test_123");
            when(paymentGateway.getStatus("pi_test_123"))
                .thenReturn(pspResult(PspPaymentState.SUCCEEDED, 0L));

            job.recoverPayment(p);

            verify(txHelper).reconcileDirectToCaptured(p.getId(), "pi_test_123", 10000L);
        }

        @Test
        @DisplayName("PSP says processing → skips")
        void pspProcessing_skips() {
            Payment p = payment(PaymentStatus.AUTHORIZE_PENDING, "pi_test_123");
            when(paymentGateway.getStatus("pi_test_123"))
                .thenReturn(pspResult(PspPaymentState.PROCESSING));

            RecoveryOutcome outcome = job.recoverPayment(p);

            assertThat(outcome).isEqualTo(RecoveryOutcome.SKIPPED);
            verify(txHelper, never()).completeAuthorization(any(), any());
            verify(txHelper, never()).resolveStaleAuthorizationFailed(any(), any());
        }

        @Test
        @DisplayName("PSP says canceled → marks failed")
        void pspCanceled_marksFailed() {
            Payment p = payment(PaymentStatus.AUTHORIZE_PENDING, "pi_test_123");
            when(paymentGateway.getStatus("pi_test_123"))
                .thenReturn(pspResult(PspPaymentState.CANCELED));

            RecoveryOutcome outcome = job.recoverPayment(p);

            assertThat(outcome).isEqualTo(RecoveryOutcome.MARKED_FAILED);
            verify(txHelper).resolveStaleAuthorizationFailed(
                eq(p.getId()), eq("psp_status_canceled"));
        }

        @Test
        @DisplayName("PSP says requires_payment_method → marks failed")
        void pspRequiresPaymentMethod_marksFailed() {
            Payment p = payment(PaymentStatus.AUTHORIZE_PENDING, "pi_test_123");
            when(paymentGateway.getStatus("pi_test_123"))
                .thenReturn(pspResult(PspPaymentState.REQUIRES_PAYMENT_METHOD));

            RecoveryOutcome outcome = job.recoverPayment(p);

            assertThat(outcome).isEqualTo(RecoveryOutcome.MARKED_FAILED);
        }
    }

    // --- CAPTURE_PENDING ---

    @Nested
    @DisplayName("CAPTURE_PENDING recovery")
    class CapturePending {

        @Test
        @DisplayName("No PSP reference → reverts to AUTHORIZED")
        void noPspReference_reverts() {
            Payment p = payment(PaymentStatus.CAPTURE_PENDING, null);

            RecoveryOutcome outcome = job.recoverPayment(p);

            assertThat(outcome).isEqualTo(RecoveryOutcome.REVERTED);
            verify(txHelper).resolveStaleCaptureFailed(
                eq(p.getId()), eq("no_psp_reference_after_threshold"));
            verifyNoInteractions(paymentGateway);
        }

        @Test
        @DisplayName("Blank PSP reference → reverts to AUTHORIZED")
        void blankPspReference_reverts() {
            Payment p = payment(PaymentStatus.CAPTURE_PENDING, "  ");

            RecoveryOutcome outcome = job.recoverPayment(p);

            assertThat(outcome).isEqualTo(RecoveryOutcome.REVERTED);
            verify(txHelper).resolveStaleCaptureFailed(
                eq(p.getId()), eq("no_psp_reference_after_threshold"));
            verifyNoInteractions(paymentGateway);
        }

        @Test
        @DisplayName("PSP says captured → completes capture")
        void pspCaptured_completesCapture() {
            Payment p = payment(PaymentStatus.CAPTURE_PENDING, "pi_test_456");
            when(paymentGateway.getStatus("pi_test_456"))
                .thenReturn(pspResult(PspPaymentState.SUCCEEDED, 10000L));

            RecoveryOutcome outcome = job.recoverPayment(p);

            assertThat(outcome).isEqualTo(RecoveryOutcome.COMPLETED_FORWARD);
            verify(txHelper).completeCaptured(p.getId(), 10000L);
        }

        @Test
        @DisplayName("PSP says captured with null amount → uses payment amountCents")
        void pspCapturedNullAmount_usesPaymentAmount() {
            Payment p = payment(PaymentStatus.CAPTURE_PENDING, "pi_test_456");
            when(paymentGateway.getStatus("pi_test_456"))
                .thenReturn(GatewayStatusResult.of(PspPaymentState.SUCCEEDED, "succeeded", null));

            job.recoverPayment(p);

            verify(txHelper).completeCaptured(p.getId(), 10000L);
        }

        @Test
        @DisplayName("PSP says still authorized → reverts to AUTHORIZED")
        void pspAuthorized_reverts() {
            Payment p = payment(PaymentStatus.CAPTURE_PENDING, "pi_test_456");
            when(paymentGateway.getStatus("pi_test_456"))
                .thenReturn(pspResult(PspPaymentState.REQUIRES_CAPTURE));

            RecoveryOutcome outcome = job.recoverPayment(p);

            assertThat(outcome).isEqualTo(RecoveryOutcome.REVERTED);
            verify(txHelper).resolveStaleCaptureFailed(
                eq(p.getId()), eq("psp_still_authorized_capture_not_applied"));
        }

        @Test
        @DisplayName("PSP says canceled → reverts to AUTHORIZED")
        void pspCanceled_reverts() {
            Payment p = payment(PaymentStatus.CAPTURE_PENDING, "pi_test_456");
            when(paymentGateway.getStatus("pi_test_456"))
                .thenReturn(pspResult(PspPaymentState.CANCELED));

            RecoveryOutcome outcome = job.recoverPayment(p);

            assertThat(outcome).isEqualTo(RecoveryOutcome.REVERTED);
            verify(txHelper).resolveStaleCaptureFailed(
                eq(p.getId()), eq("psp_canceled_during_capture"));
        }

        @Test
        @DisplayName("PSP says processing → skips")
        void pspProcessing_skips() {
            Payment p = payment(PaymentStatus.CAPTURE_PENDING, "pi_test_456");
            when(paymentGateway.getStatus("pi_test_456"))
                .thenReturn(pspResult(PspPaymentState.PROCESSING));

            RecoveryOutcome outcome = job.recoverPayment(p);

            assertThat(outcome).isEqualTo(RecoveryOutcome.SKIPPED);
            verify(txHelper, never()).completeCaptured(any(), anyLong());
            verify(txHelper, never()).resolveStaleCaptureFailed(any(), any());
        }

        @Test
        @DisplayName("PSP says unknown → skips")
        void pspUnknown_skips() {
            Payment p = payment(PaymentStatus.CAPTURE_PENDING, "pi_test_456");
            when(paymentGateway.getStatus("pi_test_456"))
                .thenReturn(pspResult(PspPaymentState.UNKNOWN));

            RecoveryOutcome outcome = job.recoverPayment(p);

            assertThat(outcome).isEqualTo(RecoveryOutcome.SKIPPED);
        }
    }

    // --- VOID_PENDING ---

    @Nested
    @DisplayName("VOID_PENDING recovery")
    class VoidPending {

        @Test
        @DisplayName("No PSP reference → reverts to AUTHORIZED")
        void noPspReference_reverts() {
            Payment p = payment(PaymentStatus.VOID_PENDING, null);

            RecoveryOutcome outcome = job.recoverPayment(p);

            assertThat(outcome).isEqualTo(RecoveryOutcome.REVERTED);
            verify(txHelper).resolveStaleVoidFailed(
                eq(p.getId()), eq("no_psp_reference_after_threshold"));
            verifyNoInteractions(paymentGateway);
        }

        @Test
        @DisplayName("Blank PSP reference → reverts to AUTHORIZED")
        void blankPspReference_reverts() {
            Payment p = payment(PaymentStatus.VOID_PENDING, "  ");

            RecoveryOutcome outcome = job.recoverPayment(p);

            assertThat(outcome).isEqualTo(RecoveryOutcome.REVERTED);
            verify(txHelper).resolveStaleVoidFailed(
                eq(p.getId()), eq("no_psp_reference_after_threshold"));
            verifyNoInteractions(paymentGateway);
        }

        @Test
        @DisplayName("PSP says canceled → completes void")
        void pspCanceled_completesVoid() {
            Payment p = payment(PaymentStatus.VOID_PENDING, "pi_test_789");
            when(paymentGateway.getStatus("pi_test_789"))
                .thenReturn(pspResult(PspPaymentState.CANCELED));

            RecoveryOutcome outcome = job.recoverPayment(p);

            assertThat(outcome).isEqualTo(RecoveryOutcome.COMPLETED_FORWARD);
            verify(txHelper).completeVoided(p.getId());
        }

        @Test
        @DisplayName("PSP says still authorized → reverts void")
        void pspAuthorized_reverts() {
            Payment p = payment(PaymentStatus.VOID_PENDING, "pi_test_789");
            when(paymentGateway.getStatus("pi_test_789"))
                .thenReturn(pspResult(PspPaymentState.REQUIRES_CAPTURE));

            RecoveryOutcome outcome = job.recoverPayment(p);

            assertThat(outcome).isEqualTo(RecoveryOutcome.REVERTED);
            verify(txHelper).resolveStaleVoidFailed(
                eq(p.getId()), eq("psp_still_authorized_void_not_applied"));
        }

        @Test
        @DisplayName("PSP says captured → reverts void (unusual edge case)")
        void pspCaptured_reverts() {
            Payment p = payment(PaymentStatus.VOID_PENDING, "pi_test_789");
            when(paymentGateway.getStatus("pi_test_789"))
                .thenReturn(pspResult(PspPaymentState.SUCCEEDED));

            RecoveryOutcome outcome = job.recoverPayment(p);

            assertThat(outcome).isEqualTo(RecoveryOutcome.REVERTED);
            verify(txHelper).resolveStaleVoidFailed(
                eq(p.getId()), eq("psp_captured_during_void"));
        }

        @Test
        @DisplayName("PSP says processing → skips")
        void pspProcessing_skips() {
            Payment p = payment(PaymentStatus.VOID_PENDING, "pi_test_789");
            when(paymentGateway.getStatus("pi_test_789"))
                .thenReturn(pspResult(PspPaymentState.PROCESSING));

            RecoveryOutcome outcome = job.recoverPayment(p);

            assertThat(outcome).isEqualTo(RecoveryOutcome.SKIPPED);
            verify(txHelper, never()).completeVoided(any());
            verify(txHelper, never()).resolveStaleVoidFailed(any(), any());
        }

        @Test
        @DisplayName("PSP says unknown → skips")
        void pspUnknown_skips() {
            Payment p = payment(PaymentStatus.VOID_PENDING, "pi_test_789");
            when(paymentGateway.getStatus("pi_test_789"))
                .thenReturn(pspResult(PspPaymentState.UNKNOWN));

            RecoveryOutcome outcome = job.recoverPayment(p);

            assertThat(outcome).isEqualTo(RecoveryOutcome.SKIPPED);
        }
    }

    // --- Edge cases / non-pending statuses ---

    @Nested
    @DisplayName("Non-pending status")
    class NonPending {

        @Test
        @DisplayName("AUTHORIZED payment returns SKIPPED")
        void authorizedPayment_skips() {
            Payment p = payment(PaymentStatus.AUTHORIZED, "pi_test_x");
            RecoveryOutcome outcome = job.recoverPayment(p);

            assertThat(outcome).isEqualTo(RecoveryOutcome.SKIPPED);
            verifyNoInteractions(paymentGateway);
            verifyNoInteractions(txHelper);
        }
    }

    // --- Gateway exception handling ---

    @Nested
    @DisplayName("Gateway exceptions")
    class GatewayExceptions {

        @Test
        @DisplayName("Gateway exception propagates from recoverPayment")
        void gatewayException_propagates() {
            Payment p = payment(PaymentStatus.CAPTURE_PENDING, "pi_test_err");
            when(paymentGateway.getStatus("pi_test_err"))
                .thenThrow(new RuntimeException("PSP timeout"));

            org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> job.recoverPayment(p));
        }
    }
}
