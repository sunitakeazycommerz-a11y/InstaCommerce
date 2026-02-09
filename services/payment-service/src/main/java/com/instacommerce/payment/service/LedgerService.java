package com.instacommerce.payment.service;

import com.instacommerce.payment.domain.model.LedgerEntry;
import com.instacommerce.payment.domain.model.LedgerEntryType;
import com.instacommerce.payment.repository.LedgerEntryRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {
    private final LedgerEntryRepository ledgerEntryRepository;

    public LedgerService(LedgerEntryRepository ledgerEntryRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
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
        LedgerEntry debit = record(paymentId, LedgerEntryType.DEBIT, amountCents, debitAccount,
            referenceType, referenceId, description);
        LedgerEntry credit = record(paymentId, LedgerEntryType.CREDIT, amountCents, creditAccount,
            referenceType, referenceId, description);
        return List.of(debit, credit);
    }
}
