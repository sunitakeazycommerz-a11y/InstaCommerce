package com.instacommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.payment.domain.model.LedgerEntry;
import com.instacommerce.payment.domain.model.LedgerEntryType;
import com.instacommerce.payment.repository.LedgerEntryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LedgerServiceDedupTest {

    @Mock LedgerEntryRepository ledgerEntryRepository;

    SimpleMeterRegistry meterRegistry;
    LedgerService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new LedgerService(ledgerEntryRepository, meterRegistry);
    }

    private void stubSaveReturnsInput() {
        when(ledgerEntryRepository.save(any(LedgerEntry.class)))
            .thenAnswer(invocation -> {
                LedgerEntry entry = invocation.getArgument(0);
                entry.setId((long) Math.abs(entry.getEntryType().hashCode()) + 1L);
                return entry;
            });
    }

    // ── Duplicate referenced double-entry ──────────────────────────────

    @Nested
    @DisplayName("Duplicate referenced double-entry")
    class DuplicateDetection {

        @Test
        @DisplayName("Returns empty list when duplicate exists for (paymentId, referenceType, referenceId)")
        void returnsEmptyOnDuplicate() {
            UUID paymentId = UUID.randomUUID();
            String refType = "CAPTURE";
            String refId = "cap-123";

            when(ledgerEntryRepository.existsByPaymentIdAndReferenceTypeAndReferenceId(
                paymentId, refType, refId)).thenReturn(true);

            List<LedgerEntry> result = service.recordDoubleEntry(
                paymentId, 5000L, "merchant-receivable", "payment-clearing",
                refType, refId, "capture settlement");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Increments ledger.dedup.skipped metric on duplicate")
        void incrementsMetricOnDuplicate() {
            UUID paymentId = UUID.randomUUID();
            String refType = "CAPTURE";
            String refId = "cap-456";

            when(ledgerEntryRepository.existsByPaymentIdAndReferenceTypeAndReferenceId(
                paymentId, refType, refId)).thenReturn(true);

            service.recordDoubleEntry(
                paymentId, 5000L, "merchant-receivable", "payment-clearing",
                refType, refId, null);

            double count = meterRegistry.counter("ledger.dedup.skipped",
                "reference_type", refType).count();
            assertThat(count).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Does not call save on duplicate")
        void doesNotSaveOnDuplicate() {
            UUID paymentId = UUID.randomUUID();

            when(ledgerEntryRepository.existsByPaymentIdAndReferenceTypeAndReferenceId(
                paymentId, "REFUND", "ref-789")).thenReturn(true);

            service.recordDoubleEntry(
                paymentId, 3000L, "refund-expense", "payment-clearing",
                "REFUND", "ref-789", "duplicate refund");

            verify(ledgerEntryRepository, never()).save(any());
        }
    }

    // ── Non-duplicate referenced double-entry ──────────────────────────

    @Nested
    @DisplayName("Non-duplicate referenced double-entry")
    class NonDuplicate {

        @Test
        @DisplayName("Writes both debit and credit entries when no duplicate exists")
        void writesBothEntries() {
            UUID paymentId = UUID.randomUUID();
            String refType = "CAPTURE";
            String refId = "cap-new-001";

            when(ledgerEntryRepository.existsByPaymentIdAndReferenceTypeAndReferenceId(
                paymentId, refType, refId)).thenReturn(false);
            stubSaveReturnsInput();

            List<LedgerEntry> result = service.recordDoubleEntry(
                paymentId, 7500L, "merchant-receivable", "payment-clearing",
                refType, refId, "new capture");

            assertThat(result).hasSize(2);

            LedgerEntry debit = result.get(0);
            assertThat(debit.getEntryType()).isEqualTo(LedgerEntryType.DEBIT);
            assertThat(debit.getAccount()).isEqualTo("merchant-receivable");
            assertThat(debit.getAmountCents()).isEqualTo(7500L);
            assertThat(debit.getPaymentId()).isEqualTo(paymentId);
            assertThat(debit.getReferenceType()).isEqualTo(refType);
            assertThat(debit.getReferenceId()).isEqualTo(refId);

            LedgerEntry credit = result.get(1);
            assertThat(credit.getEntryType()).isEqualTo(LedgerEntryType.CREDIT);
            assertThat(credit.getAccount()).isEqualTo("payment-clearing");
            assertThat(credit.getAmountCents()).isEqualTo(7500L);
        }

        @Test
        @DisplayName("Does not increment dedup metric for non-duplicate")
        void noMetricForNonDuplicate() {
            UUID paymentId = UUID.randomUUID();

            when(ledgerEntryRepository.existsByPaymentIdAndReferenceTypeAndReferenceId(
                paymentId, "CAPTURE", "cap-new-002")).thenReturn(false);
            stubSaveReturnsInput();

            service.recordDoubleEntry(
                paymentId, 1000L, "debit-acct", "credit-acct",
                "CAPTURE", "cap-new-002", null);

            double count = meterRegistry.counter("ledger.dedup.skipped",
                "reference_type", "CAPTURE").count();
            assertThat(count).isZero();
        }
    }

    // ── Blank / null referenceId bypasses dedup guard ──────────────────

    @Nested
    @DisplayName("Blank or null referenceId bypasses dedup guard")
    class NullBlankReferenceId {

        @ParameterizedTest(name = "referenceId=\"{0}\"")
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("Writes both entries without checking repository for blank/null referenceId")
        void writesBothEntriesWithoutDedupCheck(String referenceId) {
            UUID paymentId = UUID.randomUUID();
            stubSaveReturnsInput();

            List<LedgerEntry> result = service.recordDoubleEntry(
                paymentId, 2000L, "debit-acct", "credit-acct",
                "AUTHORIZATION", referenceId, "no-ref entry");

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getEntryType()).isEqualTo(LedgerEntryType.DEBIT);
            assertThat(result.get(1).getEntryType()).isEqualTo(LedgerEntryType.CREDIT);

            verify(ledgerEntryRepository, never())
                .existsByPaymentIdAndReferenceTypeAndReferenceId(any(), any(), any());
        }
    }
}
