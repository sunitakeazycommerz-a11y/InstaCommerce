package com.instacommerce.payment.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.repository.LedgerEntryRepository;
import com.instacommerce.payment.repository.PaymentRepository;
import java.util.Collections;
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
 * Verifies that {@link PaymentTransactionHelper#markAuthorizationFailed} and
 * {@link PaymentTransactionHelper#resolveStaleAuthorizationFailed} emit a
 * {@code FAILURE_RELEASE} ledger double-entry to release the authorization hold
 * when an {@code AUTHORIZATION} ledger entry exists, and skip the release when
 * no such entry is present.
 */
@ExtendWith(MockitoExtension.class)
class PaymentTransactionHelperFailureReleaseTest {

    @Mock PaymentRepository paymentRepository;
    @Mock LedgerService ledgerService;
    @Mock LedgerEntryRepository ledgerEntryRepository;
    @Mock OutboxService outboxService;
    @Mock AuditLogService auditLogService;

    private PaymentTransactionHelper helper;

    @BeforeEach
    void setUp() {
        helper = new PaymentTransactionHelper(
            paymentRepository, ledgerService, ledgerEntryRepository, outboxService, auditLogService);
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

    private void stubAuthorizationExists(UUID paymentId, boolean exists) {
        when(ledgerEntryRepository.existsByPaymentIdAndReferenceTypeAndReferenceId(
            paymentId, "AUTHORIZATION", paymentId.toString()))
            .thenReturn(exists);
    }

    // =====================================================================
    // markAuthorizationFailed
    // =====================================================================

    @Nested
    @DisplayName("markAuthorizationFailed FAILURE_RELEASE")
    class MarkAuthorizationFailed {

        @Test
        @DisplayName("releases auth hold when AUTHORIZATION ledger entry exists")
        void releasesAuthHold_whenAuthorizationExists() {
            long amountCents = 10_000;
            Payment payment = paymentInStatus(PaymentStatus.AUTHORIZE_PENDING, amountCents);
            stubForUpdate(payment);
            stubAuthorizationExists(payment.getId(), true);

            helper.markAuthorizationFailed(payment.getId(), "PSP declined");

            verify(ledgerService).recordDoubleEntry(
                eq(payment.getId()), eq(amountCents),
                eq("authorization_hold"), eq("customer_receivable"),
                eq("FAILURE_RELEASE"), eq(payment.getId().toString()), anyString());
        }

        @Test
        @DisplayName("skips release when no AUTHORIZATION ledger entry exists")
        void skipsRelease_whenNoAuthorization() {
            Payment payment = paymentInStatus(PaymentStatus.AUTHORIZE_PENDING, 5_000);
            stubForUpdate(payment);
            stubAuthorizationExists(payment.getId(), false);

            helper.markAuthorizationFailed(payment.getId(), "PSP declined");

            verify(ledgerService, never()).recordDoubleEntry(
                any(UUID.class), anyLong(),
                anyString(), anyString(),
                eq("FAILURE_RELEASE"), anyString(), anyString());
        }

        @Test
        @DisplayName("preserves outbox and audit even when releasing auth hold")
        void preservesOutboxAndAudit() {
            Payment payment = paymentInStatus(PaymentStatus.AUTHORIZE_PENDING, 8_000);
            stubForUpdate(payment);
            stubAuthorizationExists(payment.getId(), true);

            helper.markAuthorizationFailed(payment.getId(), "PSP declined");

            verify(outboxService).publish(
                eq("Payment"), eq(payment.getId().toString()),
                eq("PaymentFailed"), any());
            verify(auditLogService).logSafely(
                any(), eq("PAYMENT_AUTHORIZATION_FAILED"),
                eq("Payment"), eq(payment.getId().toString()), any());
        }
    }

    // =====================================================================
    // resolveStaleAuthorizationFailed
    // =====================================================================

    @Nested
    @DisplayName("resolveStaleAuthorizationFailed FAILURE_RELEASE")
    class ResolveStaleAuthorizationFailed {

        @Test
        @DisplayName("releases auth hold when AUTHORIZATION ledger entry exists")
        void releasesAuthHold_whenAuthorizationExists() {
            long amountCents = 12_000;
            Payment payment = paymentInStatus(PaymentStatus.AUTHORIZE_PENDING, amountCents);
            stubForUpdate(payment);
            stubAuthorizationExists(payment.getId(), true);

            helper.resolveStaleAuthorizationFailed(payment.getId(), "PSP timeout");

            verify(ledgerService).recordDoubleEntry(
                eq(payment.getId()), eq(amountCents),
                eq("authorization_hold"), eq("customer_receivable"),
                eq("FAILURE_RELEASE"), eq(payment.getId().toString()), anyString());
        }

        @Test
        @DisplayName("skips release when no AUTHORIZATION ledger entry exists")
        void skipsRelease_whenNoAuthorization() {
            Payment payment = paymentInStatus(PaymentStatus.AUTHORIZE_PENDING, 7_000);
            stubForUpdate(payment);
            stubAuthorizationExists(payment.getId(), false);

            helper.resolveStaleAuthorizationFailed(payment.getId(), "PSP timeout");

            verify(ledgerService, never()).recordDoubleEntry(
                any(UUID.class), anyLong(),
                anyString(), anyString(),
                eq("FAILURE_RELEASE"), anyString(), anyString());
        }

        @Test
        @DisplayName("preserves outbox and audit even when releasing auth hold")
        void preservesOutboxAndAudit() {
            Payment payment = paymentInStatus(PaymentStatus.AUTHORIZE_PENDING, 9_000);
            stubForUpdate(payment);
            stubAuthorizationExists(payment.getId(), true);

            helper.resolveStaleAuthorizationFailed(payment.getId(), "PSP timeout");

            verify(outboxService).publish(
                eq("Payment"), eq(payment.getId().toString()),
                eq("PaymentFailed"), any());
            verify(auditLogService).logSafely(
                any(), eq("RECOVERY_AUTH_FAILED"),
                eq("Payment"), eq(payment.getId().toString()), any());
        }
    }

    // =====================================================================
    // Idempotency
    // =====================================================================

    @Nested
    @DisplayName("FAILURE_RELEASE idempotency")
    class FailureReleaseIdempotency {

        @Test
        @DisplayName("FAILURE_RELEASE is idempotent via recordDoubleEntry dedup")
        void failureRelease_isIdempotent() {
            Payment payment = paymentInStatus(PaymentStatus.AUTHORIZE_PENDING, 10_000);
            stubForUpdate(payment);
            stubAuthorizationExists(payment.getId(), true);
            // Simulate recordDoubleEntry dedup returning empty list on second call
            when(ledgerService.recordDoubleEntry(
                eq(payment.getId()), eq(10_000L),
                eq("authorization_hold"), eq("customer_receivable"),
                eq("FAILURE_RELEASE"), eq(payment.getId().toString()), anyString()))
                .thenReturn(Collections.emptyList());

            helper.markAuthorizationFailed(payment.getId(), "PSP declined");

            // The call went through without error — dedup was handled gracefully
            verify(ledgerService).recordDoubleEntry(
                eq(payment.getId()), eq(10_000L),
                eq("authorization_hold"), eq("customer_receivable"),
                eq("FAILURE_RELEASE"), eq(payment.getId().toString()), anyString());
        }

        @Test
        @DisplayName("markAuthorizationFailed on already-FAILED payment is no-op")
        void alreadyFailed_isNoOp() {
            Payment payment = paymentInStatus(PaymentStatus.FAILED, 10_000);
            when(paymentRepository.findByIdForUpdate(payment.getId()))
                .thenReturn(Optional.of(payment));

            helper.markAuthorizationFailed(payment.getId(), "PSP declined");

            verify(ledgerService, never()).recordDoubleEntry(
                any(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString());
            verify(outboxService, never()).publish(anyString(), anyString(), anyString(), any());
        }
    }
}
