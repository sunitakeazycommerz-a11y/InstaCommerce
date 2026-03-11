package com.instacommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class LedgerServiceDedupRaceTest {

    @Mock LedgerEntryRepository ledgerEntryRepository;
    @Mock PlatformTransactionManager transactionManager;

    SimpleMeterRegistry meterRegistry;
    LedgerService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        // Lenient: not all tests reach the TransactionTemplate code path
        org.mockito.Mockito.lenient().when(transactionManager.getTransaction(any()))
            .thenReturn(new SimpleTransactionStatus());
        service = new LedgerService(ledgerEntryRepository, meterRegistry, transactionManager);
    }

    private void stubSaveReturnsInput() {
        when(ledgerEntryRepository.save(any(LedgerEntry.class)))
            .thenAnswer(invocation -> {
                LedgerEntry entry = invocation.getArgument(0);
                entry.setId((long) Math.abs(entry.getEntryType().hashCode()) + 1L);
                return entry;
            });
    }

    // ── Dedup race condition (V14 unique index conflict) ───────────────

    @Nested
    @DisplayName("Dedup race condition (DataIntegrityViolationException)")
    class DedupRace {

        @Test
        @DisplayName("Returns empty list when concurrent insert triggers unique constraint violation")
        void returnsEmptyOnRace() {
            UUID paymentId = UUID.randomUUID();
            String refType = "CAPTURE";
            String refId = "cap-race-001";

            when(ledgerEntryRepository.existsByPaymentIdAndReferenceTypeAndReferenceId(
                paymentId, refType, refId)).thenReturn(false);
            when(ledgerEntryRepository.save(any(LedgerEntry.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate key violates unique constraint"));

            List<LedgerEntry> result = service.recordDoubleEntry(
                paymentId, 5000L, "merchant-receivable", "payment-clearing",
                refType, refId, "capture settlement");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Does not propagate DataIntegrityViolationException to caller")
        void doesNotThrowOnRace() {
            UUID paymentId = UUID.randomUUID();
            String refType = "REFUND";
            String refId = "ref-race-002";

            when(ledgerEntryRepository.existsByPaymentIdAndReferenceTypeAndReferenceId(
                paymentId, refType, refId)).thenReturn(false);
            when(ledgerEntryRepository.save(any(LedgerEntry.class)))
                .thenThrow(new DataIntegrityViolationException("idx_ledger_entries_dedup"));

            assertThatCode(() -> service.recordDoubleEntry(
                paymentId, 3000L, "refund-expense", "payment-clearing",
                refType, refId, "concurrent refund"))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Increments ledger.dedup.race_caught metric on race condition")
        void incrementsRaceMetric() {
            UUID paymentId = UUID.randomUUID();
            String refType = "CAPTURE";
            String refId = "cap-race-003";

            when(ledgerEntryRepository.existsByPaymentIdAndReferenceTypeAndReferenceId(
                paymentId, refType, refId)).thenReturn(false);
            when(ledgerEntryRepository.save(any(LedgerEntry.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint violation"));

            service.recordDoubleEntry(
                paymentId, 2500L, "merchant-receivable", "payment-clearing",
                refType, refId, null);

            double count = meterRegistry.counter("ledger.dedup.race_caught",
                "reference_type", refType).count();
            assertThat(count).isEqualTo(1.0);
        }
    }

    // ── Normal (non-racy) path still works ─────────────────────────────

    @Nested
    @DisplayName("Normal path (no race)")
    class NormalPath {

        @Test
        @DisplayName("Creates both debit and credit entries when no conflict occurs")
        void writesBothEntries() {
            UUID paymentId = UUID.randomUUID();
            String refType = "CAPTURE";
            String refId = "cap-normal-001";

            when(ledgerEntryRepository.existsByPaymentIdAndReferenceTypeAndReferenceId(
                paymentId, refType, refId)).thenReturn(false);
            stubSaveReturnsInput();

            List<LedgerEntry> result = service.recordDoubleEntry(
                paymentId, 7500L, "merchant-receivable", "payment-clearing",
                refType, refId, "normal capture");

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getEntryType()).isEqualTo(LedgerEntryType.DEBIT);
            assertThat(result.get(0).getAccount()).isEqualTo("merchant-receivable");
            assertThat(result.get(1).getEntryType()).isEqualTo(LedgerEntryType.CREDIT);
            assertThat(result.get(1).getAccount()).isEqualTo("payment-clearing");
        }

        @Test
        @DisplayName("Does not increment race metric on normal path")
        void noRaceMetricOnNormalPath() {
            UUID paymentId = UUID.randomUUID();
            String refType = "CAPTURE";
            String refId = "cap-normal-002";

            when(ledgerEntryRepository.existsByPaymentIdAndReferenceTypeAndReferenceId(
                paymentId, refType, refId)).thenReturn(false);
            stubSaveReturnsInput();

            service.recordDoubleEntry(
                paymentId, 1000L, "debit-acct", "credit-acct",
                refType, refId, null);

            double count = meterRegistry.counter("ledger.dedup.race_caught",
                "reference_type", refType).count();
            assertThat(count).isZero();
        }
    }

    // ── App-level dedup still bypasses record calls ────────────────────

    @Nested
    @DisplayName("App-level dedup still skips record calls")
    class AppLevelDedup {

        @Test
        @DisplayName("Returns empty list and skips save when app-level dedup finds existing entry")
        void appLevelDedupReturnsEmpty() {
            UUID paymentId = UUID.randomUUID();
            String refType = "CAPTURE";
            String refId = "cap-dup-001";

            when(ledgerEntryRepository.existsByPaymentIdAndReferenceTypeAndReferenceId(
                paymentId, refType, refId)).thenReturn(true);

            List<LedgerEntry> result = service.recordDoubleEntry(
                paymentId, 4000L, "merchant-receivable", "payment-clearing",
                refType, refId, "already captured");

            assertThat(result).isEmpty();
            verify(ledgerEntryRepository, never()).save(any());
        }
    }
}
