package com.instacommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Tests for Wave 15 Lane D hardening: VOIDED rule (Rule 6) and pagination.
 */
@ExtendWith(MockitoExtension.class)
class LedgerVerificationHardeningTest {

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

    /** Sets up a single-page scenario returning one paymentId with balanced debits/credits. */
    private void stubSinglePageWithPayment(UUID paymentId, Payment payment, List<LedgerBalanceSummary> summaries) {
        when(ledgerEntryRepository.findDistinctPaymentIdsWithEntriesSince(any(Instant.class), eq(PageRequest.of(0, 100))))
            .thenReturn(List.of(paymentId));
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        when(ledgerEntryRepository.sumByPaymentIdGrouped(paymentId)).thenReturn(summaries);
    }

    // ========================================================================
    // Rule 6: VOIDED payments should not have unreleased authorization holds
    // ========================================================================

    @Nested
    @DisplayName("Rule 6 – VOIDED_AUTH_HOLD_UNRELEASED drift")
    class VoidedAuthHoldUnreleased {

        @Test
        @DisplayName("No drift when VOIDED payment VOID credit matches AUTHORIZATION debit")
        void rule6_voidedPayment_withMatchingVoidCredit_noDrift() {
            UUID paymentId = UUID.randomUUID();
            Payment p = payment(0, 0, PaymentStatus.VOIDED);

            stubSinglePageWithPayment(paymentId, p, List.of(
                summary("AUTHORIZATION", "DEBIT", 10000),
                summary("AUTHORIZATION", "CREDIT", 10000),
                summary("VOID", "DEBIT", 10000),
                summary("VOID", "CREDIT", 10000)
            ));

            job.verifyLedgerIntegrity();

            assertThat(counterValue("ledger.verification.drift", "VOIDED_AUTH_HOLD_UNRELEASED")).isZero();
        }

        @Test
        @DisplayName("Drift detected when VOIDED payment has no VOID credit at all")
        void rule6_voidedPayment_missingVoidCredit_detectsDrift() {
            UUID paymentId = UUID.randomUUID();
            Payment p = payment(0, 0, PaymentStatus.VOIDED);

            stubSinglePageWithPayment(paymentId, p, List.of(
                summary("AUTHORIZATION", "DEBIT", 10000),
                summary("AUTHORIZATION", "CREDIT", 10000)
                // No VOID entries at all
            ));

            job.verifyLedgerIntegrity();

            assertThat(counterValue("ledger.verification.drift", "VOIDED_AUTH_HOLD_UNRELEASED")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Drift detected when VOIDED payment VOID credit is less than AUTHORIZATION debit")
        void rule6_voidedPayment_partialVoidCredit_detectsDrift() {
            UUID paymentId = UUID.randomUUID();
            Payment p = payment(0, 0, PaymentStatus.VOIDED);

            stubSinglePageWithPayment(paymentId, p, List.of(
                summary("AUTHORIZATION", "DEBIT", 10000),
                summary("AUTHORIZATION", "CREDIT", 10000),
                summary("VOID", "DEBIT", 6000),
                summary("VOID", "CREDIT", 6000)  // 6000 < 10000 → partial release
            ));

            job.verifyLedgerIntegrity();

            assertThat(counterValue("ledger.verification.drift", "VOIDED_AUTH_HOLD_UNRELEASED")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("No drift when VOIDED payment has no AUTHORIZATION debit at all")
        void rule6_voidedPayment_noAuthDebit_noDrift() {
            UUID paymentId = UUID.randomUUID();
            Payment p = payment(0, 0, PaymentStatus.VOIDED);

            stubSinglePageWithPayment(paymentId, p, List.of(
                // No AUTHORIZATION entries — voided before auth completed
            ));

            job.verifyLedgerIntegrity();

            assertThat(counterValue("ledger.verification.drift", "VOIDED_AUTH_HOLD_UNRELEASED")).isZero();
        }

        @Test
        @DisplayName("Rule 6 does not trigger for non-VOIDED (CAPTURED) payments")
        void rule6_nonVoidedPayment_skipsRule() {
            UUID paymentId = UUID.randomUUID();
            Payment p = payment(10000, 0, PaymentStatus.CAPTURED);

            stubSinglePageWithPayment(paymentId, p, List.of(
                summary("AUTHORIZATION", "DEBIT", 10000),
                summary("AUTHORIZATION", "CREDIT", 10000),
                summary("CAPTURE", "DEBIT", 10000),
                summary("CAPTURE", "CREDIT", 10000)
                // No VOID entries — legitimate for CAPTURED
            ));

            job.verifyLedgerIntegrity();

            assertThat(counterValue("ledger.verification.drift", "VOIDED_AUTH_HOLD_UNRELEASED")).isZero();
        }
    }

    // ========================================================================
    // Pagination
    // ========================================================================

    @Nested
    @DisplayName("Pagination in doVerify()")
    class Pagination {

        @Test
        @DisplayName("Single page processes all payments when batch covers everything")
        void pagination_singlePage_processesAll() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            Payment p1 = payment(10000, 0, PaymentStatus.CAPTURED);
            Payment p2 = payment(5000, 0, PaymentStatus.CAPTURED);

            // Return 2 results (< batchSize 100) → single page
            when(ledgerEntryRepository.findDistinctPaymentIdsWithEntriesSince(any(Instant.class), eq(PageRequest.of(0, 100))))
                .thenReturn(List.of(id1, id2));
            when(paymentRepository.findById(id1)).thenReturn(Optional.of(p1));
            when(paymentRepository.findById(id2)).thenReturn(Optional.of(p2));
            when(ledgerEntryRepository.sumByPaymentIdGrouped(id1)).thenReturn(List.of(
                summary("AUTHORIZATION", "DEBIT", 10000),
                summary("AUTHORIZATION", "CREDIT", 10000),
                summary("CAPTURE", "DEBIT", 10000),
                summary("CAPTURE", "CREDIT", 10000)
            ));
            when(ledgerEntryRepository.sumByPaymentIdGrouped(id2)).thenReturn(List.of(
                summary("AUTHORIZATION", "DEBIT", 10000),
                summary("AUTHORIZATION", "CREDIT", 10000),
                summary("CAPTURE", "DEBIT", 5000),
                summary("CAPTURE", "CREDIT", 5000)
            ));

            job.verifyLedgerIntegrity();

            assertThat(counterValue("ledger.verification.checked", "total")).isEqualTo(2.0);
            assertThat(counterValue("ledger.verification.errors", "MAX_PAGES_REACHED")).isZero();
        }

        @Test
        @DisplayName("Multiple pages are processed when batch size is exceeded")
        void pagination_multiplePages_processesAll() {
            // Use a job with batchSize=2 to make pagination easier to test
            LedgerIntegrityVerificationJob smallBatchJob = new LedgerIntegrityVerificationJob(
                ledgerEntryRepository, paymentRepository, meterRegistry,
                /* lookbackMinutes= */ 60, /* batchSize= */ 2);

            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            UUID id3 = UUID.randomUUID();
            Payment p1 = payment(10000, 0, PaymentStatus.CAPTURED);
            Payment p2 = payment(10000, 0, PaymentStatus.CAPTURED);
            Payment p3 = payment(10000, 0, PaymentStatus.CAPTURED);

            // Page 0: full batch (2 results)
            when(ledgerEntryRepository.findDistinctPaymentIdsWithEntriesSince(any(Instant.class), eq(PageRequest.of(0, 2))))
                .thenReturn(List.of(id1, id2));
            // Page 1: partial batch (1 result) → last page
            when(ledgerEntryRepository.findDistinctPaymentIdsWithEntriesSince(any(Instant.class), eq(PageRequest.of(1, 2))))
                .thenReturn(List.of(id3));

            for (UUID id : List.of(id1, id2, id3)) {
                Payment p = id.equals(id1) ? p1 : id.equals(id2) ? p2 : p3;
                when(paymentRepository.findById(id)).thenReturn(Optional.of(p));
                when(ledgerEntryRepository.sumByPaymentIdGrouped(id)).thenReturn(List.of(
                    summary("AUTHORIZATION", "DEBIT", 10000),
                    summary("AUTHORIZATION", "CREDIT", 10000),
                    summary("CAPTURE", "DEBIT", 10000),
                    summary("CAPTURE", "CREDIT", 10000)
                ));
            }

            smallBatchJob.verifyLedgerIntegrity();

            assertThat(counterValue("ledger.verification.checked", "total")).isEqualTo(3.0);
            assertThat(counterValue("ledger.verification.errors", "MAX_PAGES_REACHED")).isZero();
        }

        @Test
        @DisplayName("Empty first page returns immediately with no metrics")
        void pagination_emptyFirstPage_returnsImmediately() {
            when(ledgerEntryRepository.findDistinctPaymentIdsWithEntriesSince(any(Instant.class), any(Pageable.class)))
                .thenReturn(Collections.emptyList());

            job.verifyLedgerIntegrity();

            assertThat(counterValue("ledger.verification.checked", "total")).isZero();
            assertThat(counterValue("ledger.verification.errors", "MAX_PAGES_REACHED")).isZero();
        }

        @Test
        @DisplayName("Max pages cap is enforced and logged")
        void pagination_maxPagesCap_logsWarning() {
            // Use batchSize=1 so each page has exactly 1 payment → 50 pages = 50 payments at cap
            LedgerIntegrityVerificationJob tinyBatchJob = new LedgerIntegrityVerificationJob(
                ledgerEntryRepository, paymentRepository, meterRegistry,
                /* lookbackMinutes= */ 60, /* batchSize= */ 1);

            // Generate 50 unique payment IDs (one per page, each page full at batchSize=1)
            List<UUID> allIds = IntStream.range(0, 50)
                .mapToObj(i -> UUID.randomUUID())
                .toList();

            // Each page returns exactly 1 result (== batchSize) so pagination continues
            for (int page = 0; page < 50; page++) {
                UUID id = allIds.get(page);
                when(ledgerEntryRepository.findDistinctPaymentIdsWithEntriesSince(
                    any(Instant.class), eq(PageRequest.of(page, 1))))
                    .thenReturn(List.of(id));

                Payment p = payment(10000, 0, PaymentStatus.CAPTURED);
                when(paymentRepository.findById(id)).thenReturn(Optional.of(p));
                when(ledgerEntryRepository.sumByPaymentIdGrouped(id)).thenReturn(List.of(
                    summary("AUTHORIZATION", "DEBIT", 10000),
                    summary("AUTHORIZATION", "CREDIT", 10000),
                    summary("CAPTURE", "DEBIT", 10000),
                    summary("CAPTURE", "CREDIT", 10000)
                ));
            }

            tinyBatchJob.verifyLedgerIntegrity();

            assertThat(counterValue("ledger.verification.checked", "total")).isEqualTo(50.0);
            assertThat(counterValue("ledger.verification.errors", "MAX_PAGES_REACHED")).isEqualTo(1.0);
        }
    }
}
