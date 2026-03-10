package com.instacommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.instacommerce.payment.domain.model.Refund;
import com.instacommerce.payment.domain.model.RefundStatus;
import com.instacommerce.payment.gateway.GatewayRefundStatusResult;
import com.instacommerce.payment.gateway.GatewayRefundStatusResult.PspRefundState;
import com.instacommerce.payment.gateway.PaymentGateway;
import com.instacommerce.payment.repository.RefundRepository;
import com.instacommerce.payment.service.StalePendingRefundRecoveryJob.RecoveryOutcome;
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
class StalePendingRefundRecoveryJobTest {

    @Mock RefundRepository refundRepository;
    @Mock PaymentGateway paymentGateway;
    @Mock RefundTransactionHelper txHelper;

    SimpleMeterRegistry meterRegistry;
    StalePendingRefundRecoveryJob job;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        job = new StalePendingRefundRecoveryJob(
            refundRepository, paymentGateway, txHelper, meterRegistry, 60, 50);
    }

    // --- Helpers ---

    private Refund refund(RefundStatus status, String pspRefundId) {
        Refund r = new Refund();
        r.setId(UUID.randomUUID());
        r.setPaymentId(UUID.randomUUID());
        r.setAmountCents(5000);
        r.setReason("customer_request");
        r.setIdempotencyKey("key-" + UUID.randomUUID());
        r.setStatus(status);
        r.setPspRefundId(pspRefundId);
        r.setCreatedAt(Instant.now().minusSeconds(7200));
        r.setUpdatedAt(Instant.now().minusSeconds(7200));
        return r;
    }

    private GatewayRefundStatusResult pspResult(PspRefundState state) {
        return GatewayRefundStatusResult.of(state, state.name().toLowerCase(), 0L);
    }

    // --- No PSP refund ID ---

    @Nested
    @DisplayName("No PSP refund ID")
    class NoPspRefundId {

        @Test
        @DisplayName("Null pspRefundId → marks failed without gateway call")
        void nullPspRefundId_marksFailed() {
            Refund r = refund(RefundStatus.PENDING, null);

            RecoveryOutcome outcome = job.recoverRefund(r);

            assertThat(outcome).isEqualTo(RecoveryOutcome.MARKED_FAILED);
            verify(txHelper).resolveStaleRefundFailed(
                eq(r.getId()), eq("no_psp_refund_id_after_threshold"));
            verifyNoInteractions(paymentGateway);
        }

        @Test
        @DisplayName("Blank pspRefundId → marks failed without gateway call")
        void blankPspRefundId_marksFailed() {
            Refund r = refund(RefundStatus.PENDING, "  ");

            RecoveryOutcome outcome = job.recoverRefund(r);

            assertThat(outcome).isEqualTo(RecoveryOutcome.MARKED_FAILED);
            verify(txHelper).resolveStaleRefundFailed(
                eq(r.getId()), eq("no_psp_refund_id_after_threshold"));
            verifyNoInteractions(paymentGateway);
        }
    }

    // --- PSP state resolution ---

    @Nested
    @DisplayName("PSP state resolution")
    class PspStateResolution {

        @Test
        @DisplayName("PSP says succeeded → completes stale refund")
        void pspSucceeded_completesRefund() {
            Refund r = refund(RefundStatus.PENDING, "re_test_123");
            when(paymentGateway.getRefundStatus("re_test_123"))
                .thenReturn(pspResult(PspRefundState.SUCCEEDED));

            RecoveryOutcome outcome = job.recoverRefund(r);

            assertThat(outcome).isEqualTo(RecoveryOutcome.COMPLETED_FORWARD);
            verify(txHelper).completeStaleRefund(r.getId());
        }

        @Test
        @DisplayName("PSP says failed → marks failed")
        void pspFailed_marksFailed() {
            Refund r = refund(RefundStatus.PENDING, "re_test_456");
            when(paymentGateway.getRefundStatus("re_test_456"))
                .thenReturn(pspResult(PspRefundState.FAILED));

            RecoveryOutcome outcome = job.recoverRefund(r);

            assertThat(outcome).isEqualTo(RecoveryOutcome.MARKED_FAILED);
            verify(txHelper).resolveStaleRefundFailed(
                eq(r.getId()), eq("psp_status_failed"));
        }

        @Test
        @DisplayName("PSP says canceled → marks failed")
        void pspCanceled_marksFailed() {
            Refund r = refund(RefundStatus.PENDING, "re_test_789");
            when(paymentGateway.getRefundStatus("re_test_789"))
                .thenReturn(pspResult(PspRefundState.CANCELED));

            RecoveryOutcome outcome = job.recoverRefund(r);

            assertThat(outcome).isEqualTo(RecoveryOutcome.MARKED_FAILED);
            verify(txHelper).resolveStaleRefundFailed(
                eq(r.getId()), eq("psp_status_canceled"));
        }

        @Test
        @DisplayName("PSP says pending → skips without resolving")
        void pspPending_skips() {
            Refund r = refund(RefundStatus.PENDING, "re_test_pend");
            when(paymentGateway.getRefundStatus("re_test_pend"))
                .thenReturn(pspResult(PspRefundState.PENDING));

            RecoveryOutcome outcome = job.recoverRefund(r);

            assertThat(outcome).isEqualTo(RecoveryOutcome.SKIPPED);
            verify(txHelper, never()).completeStaleRefund(any());
            verify(txHelper, never()).resolveStaleRefundFailed(any(), any());
        }

        @Test
        @DisplayName("PSP says requires_action → skips without resolving")
        void pspRequiresAction_skips() {
            Refund r = refund(RefundStatus.PENDING, "re_test_action");
            when(paymentGateway.getRefundStatus("re_test_action"))
                .thenReturn(pspResult(PspRefundState.REQUIRES_ACTION));

            RecoveryOutcome outcome = job.recoverRefund(r);

            assertThat(outcome).isEqualTo(RecoveryOutcome.SKIPPED);
            verify(txHelper, never()).completeStaleRefund(any());
            verify(txHelper, never()).resolveStaleRefundFailed(any(), any());
        }

        @Test
        @DisplayName("PSP says unknown → skips without resolving")
        void pspUnknown_skips() {
            Refund r = refund(RefundStatus.PENDING, "re_test_unk");
            when(paymentGateway.getRefundStatus("re_test_unk"))
                .thenReturn(pspResult(PspRefundState.UNKNOWN));

            RecoveryOutcome outcome = job.recoverRefund(r);

            assertThat(outcome).isEqualTo(RecoveryOutcome.SKIPPED);
            verify(txHelper, never()).completeStaleRefund(any());
            verify(txHelper, never()).resolveStaleRefundFailed(any(), any());
        }
    }

    // --- Gateway exception handling ---

    @Nested
    @DisplayName("Gateway exceptions")
    class GatewayExceptions {

        @Test
        @DisplayName("Gateway exception propagates from recoverRefund")
        void gatewayException_propagates() {
            Refund r = refund(RefundStatus.PENDING, "re_test_err");
            when(paymentGateway.getRefundStatus("re_test_err"))
                .thenThrow(new RuntimeException("PSP timeout"));

            org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> job.recoverRefund(r));
        }
    }

    // --- GatewayRefundStatusResult convenience methods ---

    @Nested
    @DisplayName("GatewayRefundStatusResult isTerminal()")
    class RefundStatusResultTerminal {

        @Test
        @DisplayName("Terminal states: SUCCEEDED, FAILED, CANCELED")
        void terminalStates() {
            assertThat(pspResult(PspRefundState.SUCCEEDED).isTerminal()).isTrue();
            assertThat(pspResult(PspRefundState.FAILED).isTerminal()).isTrue();
            assertThat(pspResult(PspRefundState.CANCELED).isTerminal()).isTrue();
        }

        @Test
        @DisplayName("Non-terminal states: PENDING, REQUIRES_ACTION, UNKNOWN")
        void nonTerminalStates() {
            assertThat(pspResult(PspRefundState.PENDING).isTerminal()).isFalse();
            assertThat(pspResult(PspRefundState.REQUIRES_ACTION).isTerminal()).isFalse();
            assertThat(pspResult(PspRefundState.UNKNOWN).isTerminal()).isFalse();
        }
    }
}
