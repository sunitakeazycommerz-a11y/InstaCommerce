package com.instacommerce.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.instacommerce.wallet.domain.model.Wallet;
import com.instacommerce.wallet.domain.model.WalletLedgerEntry;
import com.instacommerce.wallet.domain.model.WalletTransaction;
import com.instacommerce.wallet.domain.model.WalletTransaction.ReferenceType;
import com.instacommerce.wallet.repository.WalletLedgerEntryRepository;
import com.instacommerce.wallet.repository.WalletRepository;
import com.instacommerce.wallet.repository.WalletTransactionRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class WalletLedgerSymmetryTest {

    private WalletRepository walletRepository;
    private WalletTransactionRepository transactionRepository;
    private OutboxService outboxService;
    private WalletLedgerEntryRepository ledgerRepository;
    private List<WalletLedgerEntry> recordedEntries;
    private WalletLedgerService walletLedgerService;
    private WalletService walletService;

    @BeforeEach
    void setUp() {
        walletRepository = mock(WalletRepository.class);
        transactionRepository = mock(WalletTransactionRepository.class);
        outboxService = mock(OutboxService.class);
        ledgerRepository = mock(WalletLedgerEntryRepository.class);
        recordedEntries = new ArrayList<>();

        when(transactionRepository.save(any(WalletTransaction.class))).thenAnswer(inv -> {
            WalletTransaction txn = inv.getArgument(0);
            if (txn.getId() == null) {
                txn.setId(UUID.randomUUID());
            }
            return txn;
        });

        when(ledgerRepository.save(any(WalletLedgerEntry.class))).thenAnswer(inv -> {
            WalletLedgerEntry entry = inv.getArgument(0);
            if (entry.getId() == null) {
                entry.setId(UUID.randomUUID());
            }
            recordedEntries.add(entry);
            return entry;
        });

        when(ledgerRepository.computeBalanceForAccount(any())).thenAnswer(inv -> {
            String account = inv.getArgument(0);
            long credits = recordedEntries.stream()
                .filter(e -> account.equals(e.getCreditAccount()))
                .mapToLong(WalletLedgerEntry::getAmountCents)
                .sum();
            long debits = recordedEntries.stream()
                .filter(e -> account.equals(e.getDebitAccount()))
                .mapToLong(WalletLedgerEntry::getAmountCents)
                .sum();
            return credits - debits;
        });

        walletLedgerService = new WalletLedgerService(ledgerRepository);
        walletService = new WalletService(walletRepository, transactionRepository, outboxService, walletLedgerService);
    }

    @ParameterizedTest
    @EnumSource(ReferenceType.class)
    void recordsSymmetricalDoubleEntryLegsForCreditAndDebitAcrossAllReferenceTypes(ReferenceType refType) {
        UUID userId = UUID.randomUUID();
        Wallet wallet = new Wallet();
        wallet.setId(UUID.randomUUID());
        wallet.setUserId(userId);
        wallet.setBalanceCents(0L);

        when(walletRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));

        walletService.credit(userId, 1000L, refType, "credit-ref-" + refType.name(), "Credit test");
        walletService.debit(userId, 400L, refType, "debit-ref-" + refType.name(), "Debit test");

        assertThat(wallet.getBalanceCents()).isEqualTo(600L);
        assertThat(recordedEntries).hasSize(2);

        String userAccount = "user_wallet:" + userId;
        String expectedCounterparty = expectedCounterparty(refType);
        String expectedTxnType = expectedTransactionType(refType);

        WalletLedgerEntry creditEntry = recordedEntries.get(0);
        assertThat(creditEntry.getDebitAccount()).isEqualTo(expectedCounterparty);
        assertThat(creditEntry.getCreditAccount()).isEqualTo(userAccount);
        assertThat(creditEntry.getAmountCents()).isEqualTo(1000L);
        assertThat(creditEntry.getTransactionType()).isEqualTo(expectedTxnType);

        WalletLedgerEntry debitEntry = recordedEntries.get(1);
        assertThat(debitEntry.getDebitAccount()).isEqualTo(userAccount);
        assertThat(debitEntry.getCreditAccount()).isEqualTo(expectedCounterparty);
        assertThat(debitEntry.getAmountCents()).isEqualTo(400L);
        assertThat(debitEntry.getTransactionType()).isEqualTo(expectedTxnType);

        assertThat(walletLedgerService.verifyBalance(userId, wallet.getBalanceCents())).isTrue();
        assertThat(totalNetBalanceAcrossAllAccounts(recordedEntries)).isZero();
    }

    @Test
    void rejectsInvalidAmountsAndSelfTransfers() {
        UUID userId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();

        assertThatThrownBy(() -> walletService.credit(userId, 0L, ReferenceType.TOPUP, "ref-0", "zero"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> walletService.debit(userId, -50L, ReferenceType.ORDER, "ref-neg", "negative"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> walletLedgerService.recordEntry(walletId, "platform_float", "platform_float", 100L, "TOPUP", "self"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> walletLedgerService.recordEntry(walletId, "platform_float", "user_wallet:" + userId, 0L, "TOPUP", "zero"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void convenienceMethodsDelegateToSymmetricDoubleEntryLegs() {
        UUID walletId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String userAccount = "user_wallet:" + userId;

        walletLedgerService.recordTopUp(walletId, userId, 500L, "topup-1");
        walletLedgerService.recordPurchase(walletId, userId, 200L, "order-1");
        walletLedgerService.recordRefund(walletId, userId, 100L, "refund-1");
        walletLedgerService.recordPromotion(walletId, userId, 150L, "PROMOTION", "promo-1");

        assertThat(recordedEntries).hasSize(4);
        assertThat(recordedEntries.get(0).getDebitAccount()).isEqualTo("platform_float");
        assertThat(recordedEntries.get(0).getCreditAccount()).isEqualTo(userAccount);
        assertThat(recordedEntries.get(0).getTransactionType()).isEqualTo("TOPUP");

        assertThat(recordedEntries.get(1).getDebitAccount()).isEqualTo(userAccount);
        assertThat(recordedEntries.get(1).getCreditAccount()).isEqualTo("merchant_settlement");
        assertThat(recordedEntries.get(1).getTransactionType()).isEqualTo("PURCHASE");

        assertThat(recordedEntries.get(2).getDebitAccount()).isEqualTo("merchant_settlement");
        assertThat(recordedEntries.get(2).getCreditAccount()).isEqualTo(userAccount);
        assertThat(recordedEntries.get(2).getTransactionType()).isEqualTo("REFUND");

        assertThat(recordedEntries.get(3).getDebitAccount()).isEqualTo("marketing_budget");
        assertThat(recordedEntries.get(3).getCreditAccount()).isEqualTo(userAccount);
        assertThat(recordedEntries.get(3).getTransactionType()).isEqualTo("PROMOTION");

        assertThat(walletLedgerService.verifyBalance(userId, 550L)).isTrue();
        assertThat(totalNetBalanceAcrossAllAccounts(recordedEntries)).isZero();
    }

    private static String expectedCounterparty(ReferenceType refType) {
        return switch (refType) {
            case TOPUP, ADMIN_ADJUSTMENT -> "platform_float";
            case ORDER, REFUND -> "merchant_settlement";
            case CASHBACK, REFERRAL, PROMOTION -> "marketing_budget";
        };
    }

    private static String expectedTransactionType(ReferenceType refType) {
        return refType == ReferenceType.ORDER ? "PURCHASE" : refType.name();
    }

    private static long totalNetBalanceAcrossAllAccounts(List<WalletLedgerEntry> entries) {
        Map<String, Long> balances = new LinkedHashMap<>();
        for (WalletLedgerEntry entry : entries) {
            balances.merge(entry.getCreditAccount(), entry.getAmountCents(), Long::sum);
            balances.merge(entry.getDebitAccount(), -entry.getAmountCents(), Long::sum);
        }
        return balances.values().stream().mapToLong(Long::longValue).sum();
    }
}
