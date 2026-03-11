package com.instacommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
 * Verifies that partial-capture scenarios in {@link PaymentTransactionHelper}
 * emit a {@code PARTIAL_CAPTURE_RELEASE} ledger double-entry to drain the
 * uncaptured authorization remainder from {@code authorization_hold} back to
 * {@code customer_receivable}, and that full captures do not emit the release.
 */
@ExtendWith(MockitoExtension.class)
class PaymentTransactionHelperPartialCaptureReleaseTest {

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

    private Payment paymentInStatus(PaymentStatus status, long amountCents) {
        Payment p = new Payment();
        p.setId(UUID.randomUUID());
        p.setOrderId(UUID.randomUUID());
        p.setAmountCents(amountCents);
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

    // =====================================================================
    // completeCaptured — partial capture
    // =====================================================================

    @Nested
    @DisplayName("completeCaptured partial-capture release")
    class CompleteCapturedPartial {

        @Test
        @DisplayName("partial capture emits CAPTURE + PARTIAL_CAPTURE_RELEASE with correct amounts")
        void partialCapture_emitsCaptureAndRelease() {
            long amountCents = 10_000;
            long capturedCents = 6_000;
            long expectedRelease = amountCents - capturedCents; // 4_000

            Payment payment = paymentInStatus(PaymentStatus.CAPTURE_PENDING, amountCents);
            stubForUpdate(payment);

            Payment result = helper.completeCaptured(payment.getId(), capturedCents);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(result.getCapturedCents()).isEqualTo(capturedCents);

            // CAPTURE entry: authorization_hold → merchant_payable
            verify(ledgerService).recordDoubleEntry(
                eq(payment.getId()), eq(capturedCents),
                eq("authorization_hold"), eq("merchant_payable"),
                eq("CAPTURE"), eq(payment.getId().toString()), anyString());

            // PARTIAL_CAPTURE_RELEASE entry: authorization_hold → customer_receivable
            verify(ledgerService).recordDoubleEntry(
                eq(payment.getId()), eq(expectedRelease),
                eq("authorization_hold"), eq("customer_receivable"),
                eq("PARTIAL_CAPTURE_RELEASE"), eq(payment.getId().toString()), anyString());
        }

        @Test
        @DisplayName("full capture does NOT emit PARTIAL_CAPTURE_RELEASE")
        void fullCapture_doesNotEmitRelease() {
            long amountCents = 10_000;

            Payment payment = paymentInStatus(PaymentStatus.CAPTURE_PENDING, amountCents);
            stubForUpdate(payment);

            Payment result = helper.completeCaptured(payment.getId(), amountCents);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(result.getCapturedCents()).isEqualTo(amountCents);

            // CAPTURE entry should exist
            verify(ledgerService).recordDoubleEntry(
                eq(payment.getId()), eq(amountCents),
                eq("authorization_hold"), eq("merchant_payable"),
                eq("CAPTURE"), eq(payment.getId().toString()), anyString());

            // PARTIAL_CAPTURE_RELEASE must NOT be emitted
            verify(ledgerService, never()).recordDoubleEntry(
                any(UUID.class), anyLong(),
                anyString(), anyString(),
                eq("PARTIAL_CAPTURE_RELEASE"), anyString(), anyString());
        }
    }

    // =====================================================================
    // reconcileDirectToCaptured — partial capture
    // =====================================================================

    @Nested
    @DisplayName("reconcileDirectToCaptured partial-capture release")
    class ReconcilePartial {

        @Test
        @DisplayName("partial capture via reconciliation emits AUTHORIZATION + CAPTURE + PARTIAL_CAPTURE_RELEASE")
        void reconcilePartialCapture_emitsAllThreeEntries() {
            long amountCents = 8_000;
            long capturedCents = 3_000;
            long expectedRelease = amountCents - capturedCents; // 5_000

            Payment payment = paymentInStatus(PaymentStatus.AUTHORIZE_PENDING, amountCents);
            stubForUpdate(payment);

            Payment result = helper.reconcileDirectToCaptured(
                payment.getId(), "psp-reconcile-ref", capturedCents);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(result.getCapturedCents()).isEqualTo(capturedCents);

            // AUTHORIZATION entry: customer_receivable → authorization_hold
            verify(ledgerService).recordDoubleEntry(
                eq(payment.getId()), eq(amountCents),
                eq("customer_receivable"), eq("authorization_hold"),
                eq("AUTHORIZATION"), eq(payment.getId().toString()), anyString());

            // CAPTURE entry: authorization_hold → merchant_payable
            verify(ledgerService).recordDoubleEntry(
                eq(payment.getId()), eq(capturedCents),
                eq("authorization_hold"), eq("merchant_payable"),
                eq("CAPTURE"), eq(payment.getId().toString()), anyString());

            // PARTIAL_CAPTURE_RELEASE entry: authorization_hold → customer_receivable
            verify(ledgerService).recordDoubleEntry(
                eq(payment.getId()), eq(expectedRelease),
                eq("authorization_hold"), eq("customer_receivable"),
                eq("PARTIAL_CAPTURE_RELEASE"), eq(payment.getId().toString()), anyString());
        }

        @Test
        @DisplayName("full capture via reconciliation does NOT emit PARTIAL_CAPTURE_RELEASE")
        void reconcileFullCapture_doesNotEmitRelease() {
            long amountCents = 8_000;

            Payment payment = paymentInStatus(PaymentStatus.AUTHORIZE_PENDING, amountCents);
            stubForUpdate(payment);

            Payment result = helper.reconcileDirectToCaptured(
                payment.getId(), "psp-reconcile-ref", amountCents);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.CAPTURED);

            // PARTIAL_CAPTURE_RELEASE must NOT be emitted
            verify(ledgerService, never()).recordDoubleEntry(
                any(UUID.class), anyLong(),
                anyString(), anyString(),
                eq("PARTIAL_CAPTURE_RELEASE"), anyString(), anyString());
        }
    }

    // =====================================================================
    // Idempotency: already-CAPTURED payments are no-ops
    // =====================================================================

    @Nested
    @DisplayName("Idempotency guards are preserved")
    class IdempotencyGuards {

        @Test
        @DisplayName("completeCaptured on already-CAPTURED payment is no-op")
        void completeCaptured_alreadyCaptured_isNoOp() {
            Payment payment = paymentInStatus(PaymentStatus.CAPTURED, 10_000);
            payment.setCapturedCents(6_000);
            when(paymentRepository.findByIdForUpdate(payment.getId()))
                .thenReturn(Optional.of(payment));

            Payment result = helper.completeCaptured(payment.getId(), 6_000);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            verify(ledgerService, never()).recordDoubleEntry(
                any(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("reconcileDirectToCaptured on already-CAPTURED payment is no-op")
        void reconcileDirectToCaptured_alreadyCaptured_isNoOp() {
            Payment payment = paymentInStatus(PaymentStatus.CAPTURED, 10_000);
            payment.setCapturedCents(6_000);
            when(paymentRepository.findByIdForUpdate(payment.getId()))
                .thenReturn(Optional.of(payment));

            Payment result = helper.reconcileDirectToCaptured(
                payment.getId(), "psp-ref", 6_000);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            verify(ledgerService, never()).recordDoubleEntry(
                any(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString());
        }
    }
}
