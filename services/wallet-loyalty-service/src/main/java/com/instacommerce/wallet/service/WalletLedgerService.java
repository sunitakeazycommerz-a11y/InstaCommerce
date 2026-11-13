package com.instacommerce.wallet.service;

import com.instacommerce.wallet.domain.model.WalletLedgerEntry;
import com.instacommerce.wallet.domain.model.WalletTransaction.ReferenceType;
import com.instacommerce.wallet.repository.WalletLedgerEntryRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletLedgerService {
    private static final Logger log = LoggerFactory.getLogger(WalletLedgerService.class);

    private final WalletLedgerEntryRepository ledgerRepository;

    public WalletLedgerService(WalletLedgerEntryRepository ledgerRepository) {
        this.ledgerRepository = ledgerRepository;
    }

    /**
     * Records a double-entry ledger entry within the current transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordEntry(UUID walletId, String debitAccount, String creditAccount,
                            long amountCents, String transactionType, String referenceId) {
        if (walletId == null) {
            throw new IllegalArgumentException("walletId must not be null");
        }
        if (debitAccount == null || debitAccount.isBlank() || creditAccount == null || creditAccount.isBlank()) {
            throw new IllegalArgumentException("debitAccount and creditAccount must not be blank");
        }
        if (debitAccount.equals(creditAccount)) {
            throw new IllegalArgumentException("debitAccount and creditAccount must differ");
        }
        if (amountCents <= 0) {
            throw new IllegalArgumentException("amountCents must be positive");
        }
        if (transactionType == null || transactionType.isBlank()) {
            throw new IllegalArgumentException("transactionType must not be blank");
        }
        if (referenceId == null) {
            throw new IllegalArgumentException("referenceId must not be null");
        }

        WalletLedgerEntry entry = new WalletLedgerEntry();
        entry.setWalletId(walletId);
        entry.setDebitAccount(debitAccount);
        entry.setCreditAccount(creditAccount);
        entry.setAmountCents(amountCents);
        entry.setTransactionType(transactionType);
        entry.setReferenceId(referenceId);
        ledgerRepository.save(entry);
    }

    /**
     * Records balanced debit/credit double-entry legs symmetrically for either a wallet credit or debit.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordDoubleEntry(UUID walletId, UUID userId, ReferenceType refType,
                                  long amountCents, String referenceId, boolean isCredit) {
        if (refType == null) {
            throw new IllegalArgumentException("refType must not be null");
        }
        LedgerMapping mapping = mapReferenceType(refType);
        String userAccount = userAccount(userId);
        String debitAccount = isCredit ? mapping.counterpartyAccount() : userAccount;
        String creditAccount = isCredit ? userAccount : mapping.counterpartyAccount();
        recordEntry(walletId, debitAccount, creditAccount, amountCents, mapping.transactionType(), referenceId);
    }

    /** Top-up: money flows from platform float into user wallet. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordTopUp(UUID walletId, UUID userId, long amountCents, String referenceId) {
        recordDoubleEntry(walletId, userId, ReferenceType.TOPUP, amountCents, referenceId, true);
    }

    /** Purchase: money flows from user wallet to merchant settlement. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordPurchase(UUID walletId, UUID userId, long amountCents, String referenceId) {
        recordDoubleEntry(walletId, userId, ReferenceType.ORDER, amountCents, referenceId, false);
    }

    /** Refund: money flows from merchant settlement back to user wallet. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordRefund(UUID walletId, UUID userId, long amountCents, String referenceId) {
        recordDoubleEntry(walletId, userId, ReferenceType.REFUND, amountCents, referenceId, true);
    }

    /** Promotion/cashback/referral: money flows from marketing budget to user wallet. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordPromotion(UUID walletId, UUID userId, long amountCents,
                                String transactionType, String referenceId) {
        if ("CASHBACK".equals(transactionType) || "REFERRAL".equals(transactionType)
                || "PROMOTION".equals(transactionType)) {
            recordDoubleEntry(walletId, userId, ReferenceType.valueOf(transactionType), amountCents, referenceId, true);
            return;
        }
        recordEntry(walletId, "marketing_budget", userAccount(userId), amountCents, transactionType, referenceId);
    }

    /**
     * Reconciliation check: asserts that the ledger balance matches the wallet balance.
     * Returns true if consistent, false otherwise.
     */
    public boolean verifyBalance(UUID userId, long expectedBalanceCents) {
        String account = userAccount(userId);
        long ledgerBalance = ledgerRepository.computeBalanceForAccount(account);
        if (ledgerBalance != expectedBalanceCents) {
            log.error("Ledger reconciliation mismatch for {}: ledger={}, wallet={}",
                    account, ledgerBalance, expectedBalanceCents);
            return false;
        }
        return true;
    }

    private static String userAccount(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        return "user_wallet:" + userId;
    }

    private static LedgerMapping mapReferenceType(ReferenceType refType) {
        return switch (refType) {
            case TOPUP -> new LedgerMapping("platform_float", "TOPUP");
            case ORDER -> new LedgerMapping("merchant_settlement", "PURCHASE");
            case REFUND -> new LedgerMapping("merchant_settlement", "REFUND");
            case CASHBACK -> new LedgerMapping("marketing_budget", "CASHBACK");
            case REFERRAL -> new LedgerMapping("marketing_budget", "REFERRAL");
            case PROMOTION -> new LedgerMapping("marketing_budget", "PROMOTION");
            case ADMIN_ADJUSTMENT -> new LedgerMapping("platform_float", "ADMIN_ADJUSTMENT");
        };
    }

    private record LedgerMapping(String counterpartyAccount, String transactionType) {
    }
}
