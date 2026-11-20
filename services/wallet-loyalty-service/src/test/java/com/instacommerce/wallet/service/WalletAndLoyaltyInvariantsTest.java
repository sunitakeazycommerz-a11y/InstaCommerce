package com.instacommerce.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.wallet.client.OrderLookupClient;
import com.instacommerce.wallet.client.PaymentClient;
import com.instacommerce.wallet.config.WalletProperties;
import com.instacommerce.wallet.controller.WalletController;
import com.instacommerce.wallet.domain.model.LoyaltyAccount;
import com.instacommerce.wallet.domain.model.LoyaltyTier;
import com.instacommerce.wallet.domain.model.LoyaltyTransaction;
import com.instacommerce.wallet.domain.model.ReferralCode;
import com.instacommerce.wallet.domain.model.ReferralRedemption;
import com.instacommerce.wallet.domain.model.Wallet;
import com.instacommerce.wallet.domain.model.WalletTransaction;
import com.instacommerce.wallet.domain.model.WalletTransaction.ReferenceType;
import com.instacommerce.wallet.domain.model.WalletTransaction.Type;
import com.instacommerce.wallet.dto.request.CreditRequest;
import com.instacommerce.wallet.dto.request.DebitRequest;
import com.instacommerce.wallet.dto.response.LoyaltyResponse;
import com.instacommerce.wallet.dto.response.WalletTransactionResponse;
import com.instacommerce.wallet.exception.ApiException;
import com.instacommerce.wallet.exception.DuplicateTransactionException;
import com.instacommerce.wallet.kafka.OrderEventConsumer;
import com.instacommerce.wallet.repository.LoyaltyAccountRepository;
import com.instacommerce.wallet.repository.LoyaltyTransactionRepository;
import com.instacommerce.wallet.repository.ReferralCodeRepository;
import com.instacommerce.wallet.repository.ReferralRedemptionRepository;
import com.instacommerce.wallet.repository.WalletRepository;
import com.instacommerce.wallet.repository.WalletTransactionRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class WalletAndLoyaltyInvariantsTest {

    @Test
    void walletDebitIdempotentlyReturnsOriginalTransactionEvenWhenCurrentBalanceIsLower() {
        WalletRepository walletRepository = mock(WalletRepository.class);
        WalletTransactionRepository transactionRepository = mock(WalletTransactionRepository.class);
        WalletLedgerService ledgerService = mock(WalletLedgerService.class);
        OutboxService outboxService = mock(OutboxService.class);
        WalletService walletService = new WalletService(walletRepository, transactionRepository, outboxService, ledgerService);

        UUID userId = UUID.randomUUID();
        Wallet wallet = new Wallet();
        wallet.setId(UUID.randomUUID());
        wallet.setUserId(userId);
        wallet.setBalanceCents(100L);

        WalletTransaction existing = new WalletTransaction();
        existing.setWallet(wallet);
        existing.setType(Type.DEBIT);
        existing.setAmountCents(500L);
        existing.setBalanceAfterCents(400L);
        existing.setReferenceType(ReferenceType.ORDER);
        existing.setReferenceId("wf-1:wallet:debit");
        existing.setDescription("split tender");

        when(transactionRepository.findByReferenceTypeAndReferenceId(ReferenceType.ORDER, "wf-1:wallet:debit"))
            .thenReturn(Optional.of(existing));

        WalletTransactionResponse response = walletService.debit(
            userId, 500L, ReferenceType.ORDER, " wf-1:wallet:debit ", "split tender");

        assertThat(response.type()).isEqualTo("DEBIT");
        assertThat(response.amountCents()).isEqualTo(500L);
        assertThat(response.balanceAfterCents()).isEqualTo(400L);
        verifyNoInteractions(walletRepository, ledgerService, outboxService);
    }

    @Test
    void walletDebitRejectsMismatchedAmountReplayWithDuplicateTransactionException() {
        WalletRepository walletRepository = mock(WalletRepository.class);
        WalletTransactionRepository transactionRepository = mock(WalletTransactionRepository.class);
        WalletService walletService = new WalletService(
            walletRepository, transactionRepository, mock(OutboxService.class), mock(WalletLedgerService.class));

        UUID userId = UUID.randomUUID();
        Wallet wallet = new Wallet();
        wallet.setUserId(userId);

        WalletTransaction existing = new WalletTransaction();
        existing.setWallet(wallet);
        existing.setType(Type.DEBIT);
        existing.setAmountCents(500L);
        existing.setReferenceType(ReferenceType.ORDER);
        existing.setReferenceId("wf-2:wallet:debit");

        when(transactionRepository.findByReferenceTypeAndReferenceId(ReferenceType.ORDER, "wf-2:wallet:debit"))
            .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> walletService.debit(userId, 900L, ReferenceType.ORDER, "wf-2:wallet:debit", "retry"))
            .isInstanceOf(DuplicateTransactionException.class);
        verify(walletRepository, never()).save(any());
    }

    @Test
    void walletCreditRejectsBlankReferenceIdAndControllerDelegatesInternalCredit() {
        WalletService walletService = new WalletService(
            mock(WalletRepository.class),
            mock(WalletTransactionRepository.class),
            mock(OutboxService.class),
            mock(WalletLedgerService.class)
        );
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> walletService.credit(userId, 500L, ReferenceType.REFUND, "  ", "desc"))
            .isInstanceOf(IllegalArgumentException.class);

        WalletService mockWalletService = mock(WalletService.class);
        WalletController controller = new WalletController(mockWalletService, mock(PaymentClient.class));
        WalletTransactionResponse expected = new WalletTransactionResponse(
            "CREDIT", 1200L, 2200L, "REFUND", "wf-1:wallet:refund", "compensation", Instant.now());
        when(mockWalletService.credit(userId, 1200L, ReferenceType.REFUND, "wf-1:wallet:refund", "compensation"))
            .thenReturn(expected);

        ResponseEntity<WalletTransactionResponse> resp = controller.credit(
            new CreditRequest(userId, 1200L, ReferenceType.REFUND, "wf-1:wallet:refund", "compensation"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEqualTo(expected);

        Authentication auth = mock(Authentication.class);
        when(mockWalletService.debit(userId, 300L, ReferenceType.ORDER, "ord-1", "checkout"))
            .thenReturn(expected);
        controller.debit(auth, new DebitRequest(300L, "order", "ord-1", userId, "checkout"));
        verify(mockWalletService).debit(userId, 300L, ReferenceType.ORDER, "ord-1", "checkout");
    }

    @Test
    void loyaltyRejectsNonPositiveRedeemCapsEarnedPointsAndPreservesTierOnRedeem() {
        LoyaltyAccountRepository accountRepo = mock(LoyaltyAccountRepository.class);
        LoyaltyTransactionRepository txnRepo = mock(LoyaltyTransactionRepository.class);
        OutboxService outboxService = mock(OutboxService.class);
        WalletProperties properties = new WalletProperties();
        properties.getLoyalty().setMaxPointsEarnPerOrder(5000);
        properties.getLoyalty().setPointsPerRupee(1);

        LoyaltyService loyaltyService = new LoyaltyService(accountRepo, txnRepo, properties, outboxService);
        UUID userId = UUID.randomUUID();
        LoyaltyAccount account = new LoyaltyAccount();
        account.setId(UUID.randomUUID());
        account.setUserId(userId);
        account.setPointsBalance(100);
        account.setLifetimePoints(100);
        account.setTier(LoyaltyTier.BRONZE);

        when(accountRepo.findByUserIdForUpdate(userId)).thenReturn(Optional.of(account));
        when(txnRepo.saveAndFlush(any(LoyaltyTransaction.class))).thenAnswer(inv -> {
            LoyaltyTransaction txn = inv.getArgument(0);
            txn.setId(UUID.randomUUID());
            return txn;
        });

        assertThatThrownBy(() -> loyaltyService.redeemPoints(userId, -50, "ord-bad"))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_POINTS"));
        assertThatThrownBy(() -> loyaltyService.redeemPoints(userId, 0, "ord-zero"))
            .isInstanceOf(ApiException.class);

        LoyaltyResponse earned = loyaltyService.earnPoints(userId, "ord-huge", 1_000_000_000L);
        assertThat(earned.pointsBalance()).isEqualTo(5100);
        assertThat(earned.lifetimePoints()).isEqualTo(5100);
        assertThat(earned.tier()).isEqualTo("SILVER");

        LoyaltyResponse redeemed = loyaltyService.redeemPoints(userId, 4900, "ord-redeem");
        assertThat(redeemed.pointsBalance()).isEqualTo(200);
        assertThat(redeemed.tier()).isEqualTo("SILVER");
    }

    @Test
    void pointsExpirySkipsNonPositiveConfigAndPublishesOutboxWhenExpiring() {
        LoyaltyAccountRepository accountRepo = mock(LoyaltyAccountRepository.class);
        LoyaltyTransactionRepository txnRepo = mock(LoyaltyTransactionRepository.class);
        OutboxService outboxService = mock(OutboxService.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        WalletProperties disabledProps = new WalletProperties();
        disabledProps.getLoyalty().setPointsExpiryMonths(0);
        PointsExpiryJob disabledJob = new PointsExpiryJob(accountRepo, txnRepo, disabledProps, txManager, outboxService);
        disabledJob.expireOldPoints();
        verifyNoInteractions(accountRepo, txnRepo, outboxService);

        WalletProperties props = new WalletProperties();
        props.getLoyalty().setPointsExpiryMonths(12);
        PointsExpiryJob job = new PointsExpiryJob(accountRepo, txnRepo, props, txManager, outboxService);

        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        LoyaltyAccount account = new LoyaltyAccount();
        account.setId(accountId);
        account.setUserId(userId);
        account.setPointsBalance(60);
        account.setLifetimePoints(2500);
        account.setTier(LoyaltyTier.GOLD);

        LoyaltyTransaction oldEarn = new LoyaltyTransaction();
        oldEarn.setId(UUID.randomUUID());
        oldEarn.setPoints(40);
        oldEarn.setCreatedAt(Instant.now().minus(400, ChronoUnit.DAYS));

        when(accountRepo.findAllBy(any(Pageable.class))).thenReturn(new SliceImpl<>(List.of(account)));
        when(accountRepo.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
        when(txnRepo.sumEarnedBefore(eq(accountId), any())).thenReturn(40);
        when(txnRepo.sumRedeemedOrExpired(accountId)).thenReturn(0);
        when(txnRepo.findExpirableEarnTransactions(eq(accountId), any(), any()))
            .thenReturn(List.of(oldEarn))
            .thenReturn(List.of());

        job.expireOldPoints();

        assertThat(account.getPointsBalance()).isEqualTo(20);
        assertThat(account.getTier()).isEqualTo(LoyaltyTier.GOLD);
        verify(outboxService).publish(eq("Loyalty"), eq(accountId.toString()), eq("PointsExpired"), any(Map.class));
    }

    @Test
    void orderEventConsumerCapsPerOrderAndMonthlyCashback() throws Exception {
        LoyaltyService loyaltyService = mock(LoyaltyService.class);
        WalletService walletService = mock(WalletService.class);
        OrderLookupClient orderLookupClient = mock(OrderLookupClient.class);
        ObjectMapper objectMapper = new ObjectMapper();
        WalletProperties properties = new WalletProperties();
        properties.getLoyalty().setCashbackRateBps(200);
        properties.getLoyalty().setMaxCashbackPerOrderCents(1000L);
        properties.getLoyalty().setMonthlyCashbackCapCents(1500L);

        OrderEventConsumer consumer = new OrderEventConsumer(
            loyaltyService, walletService, orderLookupClient, objectMapper, properties);

        UUID userId = UUID.randomUUID();
        UUID orderId1 = UUID.randomUUID();
        UUID orderId2 = UUID.randomUUID();

        when(walletService.getMonthlyCashbackCreditedCents(eq(userId), any())).thenReturn(800L);
        String msg1 = objectMapper.writeValueAsString(Map.of(
            "eventType", "OrderDelivered",
            "orderId", orderId1.toString(),
            "userId", userId.toString(),
            "totalCents", 200_000L
        ));
        consumer.consume(msg1);
        verify(walletService).credit(eq(userId), eq(700L), eq(ReferenceType.CASHBACK), eq("cashback-" + orderId1), anyString());

        when(walletService.getMonthlyCashbackCreditedCents(eq(userId), any())).thenReturn(1500L);
        String msg2 = objectMapper.writeValueAsString(Map.of(
            "eventType", "OrderDelivered",
            "orderId", orderId2.toString(),
            "userId", userId.toString(),
            "totalCents", 50_000L
        ));
        consumer.consume(msg2);
        verify(walletService, never()).credit(eq(userId), anyLong(), eq(ReferenceType.CASHBACK), eq("cashback-" + orderId2), anyString());
    }

    @Test
    void referralNormalizesCodeHandlesConcurrentDuplicateAndEnforcesReferrerCap() {
        ReferralCodeRepository codeRepo = mock(ReferralCodeRepository.class);
        ReferralRedemptionRepository redemptionRepo = mock(ReferralRedemptionRepository.class);
        WalletService walletService = mock(WalletService.class);
        WalletProperties properties = new WalletProperties();
        properties.getLoyalty().setMaxReferralRedemptionsPerReferrer(5);

        ReferralService referralService = new ReferralService(codeRepo, redemptionRepo, walletService, properties);
        UUID referrerId = UUID.randomUUID();
        UUID newUserId = UUID.randomUUID();

        assertThatThrownBy(() -> referralService.redeemReferral("   ", newUserId))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("INVALID_REFERRAL_CODE"));

        ReferralCode cappedCode = new ReferralCode();
        cappedCode.setUserId(referrerId);
        cappedCode.setCode("CAP5");
        cappedCode.setUses(5);
        cappedCode.setMaxUses(100);
        cappedCode.setActive(true);
        cappedCode.setRewardCents(5000L);
        when(codeRepo.findByCodeForUpdate("CAP5")).thenReturn(Optional.of(cappedCode));

        assertThatThrownBy(() -> referralService.redeemReferral(" cap5 ", newUserId))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("REFERRAL_MAX_USES"));

        ReferralCode activeCode = new ReferralCode();
        activeCode.setUserId(referrerId);
        activeCode.setCode("RACE1");
        activeCode.setUses(0);
        activeCode.setMaxUses(10);
        activeCode.setActive(true);
        activeCode.setRewardCents(5000L);
        when(codeRepo.findByCodeForUpdate("RACE1")).thenReturn(Optional.of(activeCode));
        when(redemptionRepo.findByReferredUserId(newUserId)).thenReturn(Optional.empty());
        when(redemptionRepo.saveAndFlush(any(ReferralRedemption.class)))
            .thenThrow(new DataIntegrityViolationException("unique violation"));

        assertThatThrownBy(() -> referralService.redeemReferral("race1", newUserId))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("REFERRAL_ALREADY_USED"));
        verifyNoInteractions(walletService);
    }
}
