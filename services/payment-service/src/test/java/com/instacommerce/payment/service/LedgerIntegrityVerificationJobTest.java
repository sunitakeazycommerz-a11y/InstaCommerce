package com.instacommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.repository.LedgerBalanceSummary;
import com.instacommerce.payment.repository.LedgerEntryRepository;
import com.instacommerce.payment.repository.PaymentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class LedgerIntegrityVerificationJobTest {

    @Mock LedgerEntryRepository ledgerEntryRepository;
    @Mock PaymentRepository paymentRepository;

    SimpleMeterRegistry meterRegistry;
    LedgerIntegrityVerificationJob job;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        job = new LedgerIntegrityVerificationJob(
            ledgerEntryRepository, paymentRepository, meterRegistry,
            /* lookbackMinutes= */ 60, /* batchSize= */ 100);
    }

    // --- Helpers ---

    private Payment payment(long capturedCents, long refundedCents) {
        return payment(capturedCents, refundedCents, PaymentStatus.CAPTURED);
    }

    private Payment payment(long capturedCents, long refundedCents, PaymentStatus status) {
        Payment p = new Payment();
        p.setId(UUID.randomUUID());
        p.setOrderId(UUID.randomUUID());
        p.setAmountCents(10000);
        p.setCapturedCents(capturedCents);
        p.setRefundedCents(refundedCents);
        p.setCurrency("INR");
        p.setStatus(status);
        p.setPspReference("psp-" + UUID.randomUUID());
        p.setIdempotencyKey("key-" + UUID.randomUUID());
        p.setCreatedAt(Instant.now().minusSeconds(600));
        p.setUpdatedAt(Instant.now().minusSeconds(600));
        return p;
    }

    private static LedgerBalanceSummary summary(String referenceType, String entryType, long totalAmountCents) {
        return new LedgerBalanceSummary() {
            @Override public String getReferenceType() { return referenceType; }
            @Override public String getEntryType() { return entryType; }
            @Override public long getTotalAmountCents() { return totalAmountCents; }
        };
    }

    private double counterValue(String name, String statusTag) {
        return meterRegistry.counter(name, "status", statusTag).count();
    }

    // --- Empty batch / no recent payments ---

    @Nested
    @DisplayName("Empty batch – no recent payments")
    class EmptyBatch {

        @Test
        @DisplayName("Does nothing when no payment IDs are returned")
        void noPaymentIds_noProcessing() {
            when(ledgerEntryRepository.findDistinctPaymentIdsWithEntriesSince(any(Instant.class), any(Pageable.class)))
                .thenReturn(Collections.emptyList());

            job.verifyLedgerIntegrity();

            verifyNoInteractions(paymentRepository);
            assertThat(counterValue("ledger.verification.drift", "DEBIT_CREDIT_MISMATCH")).isZero();
            assertThat(counterValue("ledger.verification.drift", "REFUND_MISMATCH")).isZero();
            assertThat(counterValue("ledger.verification.drift", "CAPTURE_MISMATCH")).isZero();
            assertThat(counterValue("ledger.verification.errors", "EXCEPTION")).isZero();
            assertThat(counterValue("ledger.verification.errors", "PAYMENT_NOT_FOUND")).isZero();
        }
    }

    // --- Balanced payment ---

    @Nested
    @DisplayName("Balanced payment – no drift")
    class BalancedPayment {

        @Test
        @DisplayName("No drift metrics when debits==credits, refund and capture match")
        void balancedPayment_noDrift() {
            UUID paymentId = UUID.randomUUID();
            Payment p = payment(/* capturedCents= */ 5000, /* refundedCents= */ 1000);

            when(ledgerEntryRepository.findDistinctPaymentIdsWithEntriesSince(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(paymentId));
            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
            // Each row is a (referenceType, entryType) group; sumByEntryType aggregates across all rows
            when(ledgerEntryRepository.sumByPaymentIdGrouped(paymentId)).thenReturn(List.of(
                summary("AUTHORIZE", "DEBIT", 10000),
                summary("AUTHORIZE", "CREDIT", 10000),
                summary("CAPTURE", "DEBIT", 5000),
                summary("CAPTURE", "CREDIT", 5000),
                summary("REFUND", "DEBIT", 1000),
                summary("REFUND", "CREDIT", 1000)
            ));

            job.verifyLedgerIntegrity();

            assertThat(counterValue("ledger.verification.checked", "total")).isEqualTo(1.0);
            assertThat(counterValue("ledger.verification.drift", "DEBIT_CREDIT_MISMATCH")).isZero();
            assertThat(counterValue("ledger.verification.drift", "REFUND_MISMATCH")).isZero();
            assertThat(counterValue("ledger.verification.drift", "CAPTURE_MISMATCH")).isZero();
        }
    }

    // --- Debit / Credit mismatch ---

    @Nested
    @DisplayName("Debit/Credit mismatch drift")
    class DebitCreditMismatch {

        @Test
        @DisplayName("Drift metric increments when total debits != total credits")
        void mismatch_incrementsDriftMetric() {
            UUID paymentId = UUID.randomUUID();
            Payment p = payment(5000, 0);

            when(ledgerEntryRepository.findDistinctPaymentIdsWithEntriesSince(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(paymentId));
            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
            when(ledgerEntryRepository.sumByPaymentIdGrouped(paymentId)).thenReturn(List.of(
                summary("AUTHORIZE", "DEBIT", 10000),
                summary("AUTHORIZE", "CREDIT", 9500),  // 500¢ short → mismatch
                summary("CAPTURE", "DEBIT", 5000),
                summary("CAPTURE", "CREDIT", 5000)
            ));

            job.verifyLedgerIntegrity();

            assertThat(counterValue("ledger.verification.drift", "DEBIT_CREDIT_MISMATCH")).isEqualTo(1.0);
        }
    }

    // --- Refund mismatch ---

    @Nested
    @DisplayName("Refund mismatch drift")
    class RefundMismatch {

        @Test
        @DisplayName("Drift metric increments when ledger refund credits != payment.refundedCents")
        void refundMismatch_incrementsDriftMetric() {
            UUID paymentId = UUID.randomUUID();
            Payment p = payment(5000, /* refundedCents= */ 2000);

            when(ledgerEntryRepository.findDistinctPaymentIdsWithEntriesSince(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(paymentId));
            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
            // Debits and credits balance overall; capture matches; refund ledger short
            when(ledgerEntryRepository.sumByPaymentIdGrouped(paymentId)).thenReturn(List.of(
                summary("AUTHORIZE", "DEBIT", 10000),
                summary("AUTHORIZE", "CREDIT", 10000),
                summary("CAPTURE", "DEBIT", 5000),
                summary("CAPTURE", "CREDIT", 5000),
                summary("REFUND", "DEBIT", 1500),
                summary("REFUND", "CREDIT", 1500)    // 1500 != 2000
            ));

            job.verifyLedgerIntegrity();

            assertThat(counterValue("ledger.verification.drift", "REFUND_MISMATCH")).isEqualTo(1.0);
            assertThat(counterValue("ledger.verification.drift", "DEBIT_CREDIT_MISMATCH")).isZero();
            assertThat(counterValue("ledger.verification.drift", "CAPTURE_MISMATCH")).isZero();
        }
    }

    // --- Capture mismatch ---

    @Nested
    @DisplayName("Capture mismatch drift")
    class CaptureMismatch {

        @Test
        @DisplayName("Drift metric increments when ledger capture credits != payment.capturedCents")
        void captureMismatch_incrementsDriftMetric() {
            UUID paymentId = UUID.randomUUID();
            Payment p = payment(/* capturedCents= */ 8000, /* refundedCents= */ 0);

            when(ledgerEntryRepository.findDistinctPaymentIdsWithEntriesSince(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(paymentId));
            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
            // Balanced overall; refund is 0 (no rows); capture credit != capturedCents
            when(ledgerEntryRepository.sumByPaymentIdGrouped(paymentId)).thenReturn(List.of(
                summary("AUTHORIZE", "DEBIT", 10000),
                summary("AUTHORIZE", "CREDIT", 10000),
                summary("CAPTURE", "DEBIT", 7500),
                summary("CAPTURE", "CREDIT", 7500)    // 7500 != 8000
            ));

            job.verifyLedgerIntegrity();

            assertThat(counterValue("ledger.verification.drift", "CAPTURE_MISMATCH")).isEqualTo(1.0);
            assertThat(counterValue("ledger.verification.drift", "DEBIT_CREDIT_MISMATCH")).isZero();
            assertThat(counterValue("ledger.verification.drift", "REFUND_MISMATCH")).isZero();
        }
    }

    // --- Payment not found ---

    @Nested
    @DisplayName("Payment not found")
    class PaymentNotFound {

        @Test
        @DisplayName("Error metric increments and no exception escapes when payment is missing")
        void paymentNotFound_incrementsErrorMetric_noException() {
            UUID paymentId = UUID.randomUUID();

            when(ledgerEntryRepository.findDistinctPaymentIdsWithEntriesSince(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(paymentId));
            when(paymentRepository.findById(paymentId)).thenReturn(Optional.empty());

            job.verifyLedgerIntegrity();

            assertThat(counterValue("ledger.verification.errors", "PAYMENT_NOT_FOUND")).isEqualTo(1.0);
            assertThat(counterValue("ledger.verification.drift", "DEBIT_CREDIT_MISMATCH")).isZero();
            assertThat(counterValue("ledger.verification.drift", "REFUND_MISMATCH")).isZero();
            assertThat(counterValue("ledger.verification.drift", "CAPTURE_MISMATCH")).isZero();
            // ledger summaries should not be fetched for a missing payment
            verify(ledgerEntryRepository, never()).sumByPaymentIdGrouped(any());
        }
    }

    // --- Exception handling ---

    @Nested
    @DisplayName("Exception handling")
    class ExceptionHandling {

        @Test
        @DisplayName("Runtime exception in verifyPayment increments error metric and does not propagate")
        void unexpectedException_incrementsErrorMetric_noEscape() {
            UUID paymentId = UUID.randomUUID();

            when(ledgerEntryRepository.findDistinctPaymentIdsWithEntriesSince(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(paymentId));
            when(paymentRepository.findById(paymentId))
                .thenThrow(new RuntimeException("db connection lost"));

            job.verifyLedgerIntegrity();

            assertThat(counterValue("ledger.verification.errors", "EXCEPTION")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Exception on one payment does not prevent verification of the next")
        void exceptionOnFirst_doesNotBlockSecond() {
            UUID badId = UUID.randomUUID();
            UUID goodId = UUID.randomUUID();
            Payment p = payment(5000, 0);

            when(ledgerEntryRepository.findDistinctPaymentIdsWithEntriesSince(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(badId, goodId));
            when(paymentRepository.findById(badId))
                .thenThrow(new RuntimeException("db timeout"));
            when(paymentRepository.findById(goodId)).thenReturn(Optional.of(p));
            when(ledgerEntryRepository.sumByPaymentIdGrouped(goodId)).thenReturn(List.of(
                summary("AUTHORIZE", "DEBIT", 10000),
                summary("AUTHORIZE", "CREDIT", 10000),
                summary("CAPTURE", "DEBIT", 5000),
                summary("CAPTURE", "CREDIT", 5000)
            ));

            job.verifyLedgerIntegrity();

            assertThat(counterValue("ledger.verification.errors", "EXCEPTION")).isEqualTo(1.0);
            assertThat(counterValue("ledger.verification.checked", "total")).isEqualTo(2.0);
            // second payment should have been verified without drift
            assertThat(counterValue("ledger.verification.drift", "DEBIT_CREDIT_MISMATCH")).isZero();
        }
    }

    // --- Multiple drift types on single payment ---

    @Nested
    @DisplayName("Multiple drift types on a single payment")
    class MultipleDrifts {

        @Test
        @DisplayName("All three drift metrics increment when all rules fail simultaneously")
        void allRulesFail_allDriftMetricsIncrement() {
            UUID paymentId = UUID.randomUUID();
            Payment p = payment(/* capturedCents= */ 5000, /* refundedCents= */ 2000);

            when(ledgerEntryRepository.findDistinctPaymentIdsWithEntriesSince(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(paymentId));
            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
            when(ledgerEntryRepository.sumByPaymentIdGrouped(paymentId)).thenReturn(List.of(
                summary("AUTHORIZE", "DEBIT", 10000),
                summary("AUTHORIZE", "CREDIT", 9000),  // overall debit 10000 != credit 9000+4000+1000=14000? No...
                summary("CAPTURE", "DEBIT", 4000),
                summary("CAPTURE", "CREDIT", 4000),     // capture 4000 != 5000
                summary("REFUND", "DEBIT", 1000),
                summary("REFUND", "CREDIT", 1000)        // refund 1000 != 2000
            ));

            job.verifyLedgerIntegrity();

            // totalDebits = 10000+4000+1000=15000, totalCredits = 9000+4000+1000=14000 → mismatch
            assertThat(counterValue("ledger.verification.drift", "DEBIT_CREDIT_MISMATCH")).isEqualTo(1.0);
            assertThat(counterValue("ledger.verification.drift", "REFUND_MISMATCH")).isEqualTo(1.0);
            assertThat(counterValue("ledger.verification.drift", "CAPTURE_MISMATCH")).isEqualTo(1.0);
        }
    }

    // --- FAILED payment with unreleased authorization hold ---

    @Nested
    @DisplayName("FAILED_AUTH_HOLD_UNRELEASED drift")
    class FailedAuthHoldUnreleased {

        @Test
        @DisplayName("Drift detected when FAILED payment has authorization hold with no release")
        void failedPayment_noRelease_driftDetected() {
            UUID paymentId = UUID.randomUUID();
            Payment p = payment(0, 0, PaymentStatus.FAILED);

            when(ledgerEntryRepository.findDistinctPaymentIdsWithEntriesSince(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(paymentId));
            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
            // Authorization placed but never released
            when(ledgerEntryRepository.sumByPaymentIdGrouped(paymentId)).thenReturn(List.of(
                summary("AUTHORIZATION", "DEBIT", 10000),
                summary("AUTHORIZATION", "CREDIT", 10000)
            ));

            job.verifyLedgerIntegrity();

            assertThat(counterValue("ledger.verification.drift", "FAILED_AUTH_HOLD_UNRELEASED")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("No drift when FAILED payment authorization is fully released")
        void failedPayment_fullyReleased_noDrift() {
            UUID paymentId = UUID.randomUUID();
            Payment p = payment(0, 0, PaymentStatus.FAILED);

            when(ledgerEntryRepository.findDistinctPaymentIdsWithEntriesSince(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(paymentId));
            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
            // Authorization placed and fully released – debits==credits overall
            when(ledgerEntryRepository.sumByPaymentIdGrouped(paymentId)).thenReturn(List.of(
                summary("AUTHORIZATION", "DEBIT", 10000),
                summary("AUTHORIZATION", "CREDIT", 10000),
                summary("FAILURE_RELEASE", "DEBIT", 10000),
                summary("FAILURE_RELEASE", "CREDIT", 10000)
            ));

            job.verifyLedgerIntegrity();

            assertThat(counterValue("ledger.verification.drift", "FAILED_AUTH_HOLD_UNRELEASED")).isZero();
        }

        @Test
        @DisplayName("Drift detected when FAILED payment authorization is only partially released")
        void failedPayment_partialRelease_driftDetected() {
            UUID paymentId = UUID.randomUUID();
            Payment p = payment(0, 0, PaymentStatus.FAILED);

            when(ledgerEntryRepository.findDistinctPaymentIdsWithEntriesSince(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(paymentId));
            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
            // 10000¢ authorized but only 6000¢ released – 4000¢ still held
            when(ledgerEntryRepository.sumByPaymentIdGrouped(paymentId)).thenReturn(List.of(
                summary("AUTHORIZATION", "DEBIT", 10000),
                summary("AUTHORIZATION", "CREDIT", 10000),
                summary("FAILURE_RELEASE", "DEBIT", 6000),
                summary("FAILURE_RELEASE", "CREDIT", 6000)
            ));

            job.verifyLedgerIntegrity();

            assertThat(counterValue("ledger.verification.drift", "FAILED_AUTH_HOLD_UNRELEASED")).isEqualTo(1.0);
            // Overall debits != credits since void only partially reverses
            assertThat(counterValue("ledger.verification.drift", "DEBIT_CREDIT_MISMATCH")).isZero();
        }

        @Test
        @DisplayName("No drift for non-FAILED payment with unreleased authorization hold")
        void capturedPayment_withAuthHold_noDrift() {
            UUID paymentId = UUID.randomUUID();
            Payment p = payment(10000, 0, PaymentStatus.CAPTURED);

            when(ledgerEntryRepository.findDistinctPaymentIdsWithEntriesSince(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(paymentId));
            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
            // Authorize + capture, no void – legitimate for CAPTURED status
            when(ledgerEntryRepository.sumByPaymentIdGrouped(paymentId)).thenReturn(List.of(
                summary("AUTHORIZATION", "DEBIT", 10000),
                summary("AUTHORIZATION", "CREDIT", 10000),
                summary("CAPTURE", "DEBIT", 10000),
                summary("CAPTURE", "CREDIT", 10000)
            ));

            job.verifyLedgerIntegrity();

            assertThat(counterValue("ledger.verification.drift", "FAILED_AUTH_HOLD_UNRELEASED")).isZero();
        }
    }
}
