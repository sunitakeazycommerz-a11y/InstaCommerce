package com.instacommerce.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.wallet.config.WalletProperties;
import com.instacommerce.wallet.domain.model.LoyaltyAccount;
import com.instacommerce.wallet.domain.model.LoyaltyTier;
import com.instacommerce.wallet.domain.model.LoyaltyTransaction;
import com.instacommerce.wallet.dto.response.LoyaltyResponse;
import com.instacommerce.wallet.dto.response.WalletResponse;
import com.instacommerce.wallet.repository.LoyaltyAccountRepository;
import com.instacommerce.wallet.repository.LoyaltyTransactionRepository;
import com.instacommerce.wallet.repository.WalletLedgerEntryRepository;
import com.instacommerce.wallet.repository.WalletRepository;
import com.instacommerce.wallet.repository.WalletTransactionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;

class WalletLoyaltyHardeningTest {

    @Test
    void walletGetBalanceReturnsZeroWithoutInsertingRow() {
        WalletRepository walletRepository = mock(WalletRepository.class);
        WalletTransactionRepository txnRepo = mock(WalletTransactionRepository.class);
        OutboxService outboxService = mock(OutboxService.class);
        WalletLedgerService ledgerService = new WalletLedgerService(mock(WalletLedgerEntryRepository.class));
        WalletService service = new WalletService(walletRepository, txnRepo, outboxService, ledgerService);

        UUID userId = UUID.randomUUID();
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.empty());

        WalletResponse response = service.getBalance(userId);

        assertThat(response.balanceCents()).isZero();
        assertThat(response.currency()).isEqualTo("INR");
        verify(walletRepository, never()).save(any());
    }

    @Test
    void loyaltyGetBalanceDoesNotInsertAndEarnPublishesContractMap() {
        LoyaltyAccountRepository accountRepo = mock(LoyaltyAccountRepository.class);
        LoyaltyTransactionRepository txnRepo = mock(LoyaltyTransactionRepository.class);
        OutboxService outboxService = mock(OutboxService.class);
        WalletProperties props = new WalletProperties();
        LoyaltyService service = new LoyaltyService(accountRepo, txnRepo, props, outboxService);

        UUID userId = UUID.randomUUID();
        when(accountRepo.findByUserId(userId)).thenReturn(Optional.empty());
        LoyaltyResponse emptyBalance = service.getBalance(userId);
        assertThat(emptyBalance.pointsBalance()).isZero();
        assertThat(emptyBalance.tier()).isEqualTo("BRONZE");
        verify(accountRepo, never()).save(any());

        LoyaltyAccount account = new LoyaltyAccount();
        account.setId(UUID.randomUUID());
        account.setUserId(userId);
        when(accountRepo.findByUserIdForUpdate(userId)).thenReturn(Optional.of(account));
        when(txnRepo.saveAndFlush(any(LoyaltyTransaction.class))).thenAnswer(inv -> {
            LoyaltyTransaction t = inv.getArgument(0);
            t.setId(UUID.randomUUID());
            return t;
        });

        service.earnPoints(userId, "order-101", 25_000L);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.captor();
        verify(outboxService).publish(eq("Loyalty"), eq(account.getId().toString()), eq("PointsEarned"), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
            .containsEntry("userId", userId.toString())
            .containsEntry("orderId", "order-101")
            .containsEntry("points", 250)
            .containsEntry("tier", LoyaltyTier.BRONZE.name())
            .containsEntry("totalBalance", 250);
    }

    @Test
    void pointsExpiryJobAccountsForAlreadyRedeemedPointsAndLocksAccount() {
        LoyaltyAccountRepository accountRepo = mock(LoyaltyAccountRepository.class);
        LoyaltyTransactionRepository txnRepo = mock(LoyaltyTransactionRepository.class);
        WalletProperties props = new WalletProperties();
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        PointsExpiryJob job = new PointsExpiryJob(accountRepo, txnRepo, props, txManager);

        UUID accountId = UUID.randomUUID();
        LoyaltyAccount account = new LoyaltyAccount();
        account.setId(accountId);
        account.setUserId(UUID.randomUUID());
        account.setPointsBalance(70);

        LoyaltyTransaction oldEarn = new LoyaltyTransaction();
        oldEarn.setId(UUID.randomUUID());
        oldEarn.setAccount(account);
        oldEarn.setType(LoyaltyTransaction.Type.EARN);
        oldEarn.setPoints(100);

        Instant cutoff = Instant.now().minusSeconds(86400);
        when(accountRepo.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
        when(txnRepo.sumEarnedBefore(accountId, cutoff)).thenReturn(100);
        when(txnRepo.sumRedeemedOrExpired(accountId)).thenReturn(80);
        when(txnRepo.findExpirableEarnTransactions(eq(accountId), eq(cutoff), any(Pageable.class)))
            .thenReturn(List.of(oldEarn))
            .thenReturn(List.of());
        when(txnRepo.saveAndFlush(any(LoyaltyTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        int expired = job.expireAccountPoints(accountId, cutoff);

        assertThat(expired).isEqualTo(20);
        assertThat(account.getPointsBalance()).isEqualTo(50);
        verify(accountRepo).findByIdForUpdate(accountId);
    }
}
