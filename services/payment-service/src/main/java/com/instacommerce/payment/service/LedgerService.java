package com.instacommerce.payment.service;

import com.instacommerce.payment.domain.model.LedgerEntry;
import com.instacommerce.payment.domain.model.LedgerEntryType;
import com.instacommerce.payment.repository.LedgerEntryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {
    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final LedgerEntryRepository ledgerEntryRepository;
    private final MeterRegistry meterRegistry;

    public LedgerService(LedgerEntryRepository ledgerEntryRepository, MeterRegistry meterRegistry) {
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.meterRegistry = meterRegistry;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEntry record(UUID paymentId, LedgerEntryType entryType, long amountCents, String account,
                              String referenceType) {
        return record(paymentId, entryType, amountCents, account, referenceType, null, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEntry record(UUID paymentId, LedgerEntryType entryType, long amountCents, String account,
                              String referenceType, String referenceId, String description) {
        LedgerEntry entry = new LedgerEntry();
        entry.setPaymentId(paymentId);
        entry.setEntryType(entryType);
        entry.setAmountCents(amountCents);
        entry.setAccount(account);
        entry.setReferenceType(referenceType);
        entry.setReferenceId(referenceId);
        entry.setDescription(description);
        return ledgerEntryRepository.save(entry);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<LedgerEntry> recordDoubleEntry(UUID paymentId, long amountCents,
                                               String debitAccount, String creditAccount,
                                               String referenceType, String referenceId, String description) {
        // Application-level dedup: skip the entire double-entry if a ledger row
        // already exists for this (paymentId, referenceType, referenceId).
        // The V14 partial unique index (idx_ledger_entries_dedup) is the hard-stop
        // safety net; this check avoids hitting it under normal replay/retry paths.
        if (referenceId != null && !referenceId.isBlank()
                && ledgerEntryRepository.existsByPaymentIdAndReferenceTypeAndReferenceId(
                        paymentId, referenceType, referenceId)) {
            log.warn("Ledger dedup: skipping duplicate double-entry for payment={} referenceType={} referenceId={}",
                    paymentId, referenceType, referenceId);
            meterRegistry.counter("ledger.dedup.skipped", "reference_type", referenceType).increment();
            return Collections.emptyList();
        }

        LedgerEntry debit = record(paymentId, LedgerEntryType.DEBIT, amountCents, debitAccount,
            referenceType, referenceId, description);
        LedgerEntry credit = record(paymentId, LedgerEntryType.CREDIT, amountCents, creditAccount,
            referenceType, referenceId, description);
        return List.of(debit, credit);
    }
}
