package com.instacommerce.wallet.service;

import com.instacommerce.wallet.config.WalletProperties;
import com.instacommerce.wallet.domain.model.LoyaltyAccount;
import com.instacommerce.wallet.domain.model.LoyaltyTier;
import com.instacommerce.wallet.domain.model.LoyaltyTransaction;
import com.instacommerce.wallet.dto.response.LoyaltyResponse;
import com.instacommerce.wallet.exception.ApiException;
import com.instacommerce.wallet.exception.DuplicateTransactionException;
import com.instacommerce.wallet.repository.LoyaltyAccountRepository;
import com.instacommerce.wallet.repository.LoyaltyTransactionRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoyaltyService {
    private static final Logger log = LoggerFactory.getLogger(LoyaltyService.class);

    private final LoyaltyAccountRepository accountRepository;
    private final LoyaltyTransactionRepository transactionRepository;
    private final WalletProperties walletProperties;
    private final OutboxService outboxService;

    public LoyaltyService(LoyaltyAccountRepository accountRepository,
                          LoyaltyTransactionRepository transactionRepository,
                          WalletProperties walletProperties,
                          OutboxService outboxService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.walletProperties = walletProperties;
        this.outboxService = outboxService;
    }

    @Transactional(readOnly = true)
    public LoyaltyResponse getBalance(UUID userId) {
        LoyaltyAccount account = accountRepository.findByUserId(userId)
            .orElseGet(() -> createAccount(userId));
        return toResponse(account);
    }

    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class,
               maxAttempts = 3, backoff = @Backoff(delay = 100, multiplier = 2))
    @Transactional
    public LoyaltyResponse earnPoints(UUID userId, String orderId, long orderTotalCents) {
        LoyaltyAccount account = accountRepository.findByUserIdForUpdate(userId)
            .orElseGet(() -> createAccount(userId));

        int pointsPerRupee = walletProperties.getLoyalty().getPointsPerRupee();
        int pointsEarned = (int) ((orderTotalCents * pointsPerRupee) / 100);
        if (pointsEarned <= 0) {
            return toResponse(account);
        }

        account.setPointsBalance(account.getPointsBalance() + pointsEarned);
        account.setLifetimePoints(account.getLifetimePoints() + pointsEarned);
        accountRepository.save(account);

        LoyaltyTransaction txn = new LoyaltyTransaction();
        txn.setAccount(account);
        txn.setType(LoyaltyTransaction.Type.EARN);
        txn.setPoints(pointsEarned);
        txn.setReferenceType("ORDER");
        txn.setReferenceId(orderId);
        try {
            transactionRepository.save(txn);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateTransactionException("Transaction already exists for ref=ORDER/" + orderId);
        }

        checkTierUpgrade(account);
        log.info("Earned {} points for user={} order={}", pointsEarned, userId, orderId);

        outboxService.publish("Loyalty", account.getId().toString(), "PointsEarned", txn.getId());
        return toResponse(account);
    }

    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class,
               maxAttempts = 3, backoff = @Backoff(delay = 100, multiplier = 2))
    @Transactional
    public LoyaltyResponse redeemPoints(UUID userId, int points, String orderId) {
        LoyaltyAccount account = accountRepository.findByUserIdForUpdate(userId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "LOYALTY_ACCOUNT_NOT_FOUND",
                "Loyalty account not found for user: " + userId));

        if (account.getPointsBalance() < points) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_POINTS",
                String.format("Insufficient points: available=%d, requested=%d", account.getPointsBalance(), points));
        }

        account.setPointsBalance(account.getPointsBalance() - points);
        accountRepository.save(account);

        LoyaltyTransaction txn = new LoyaltyTransaction();
        txn.setAccount(account);
        txn.setType(LoyaltyTransaction.Type.REDEEM);
        txn.setPoints(points);
        txn.setReferenceType("REDEMPTION");
        txn.setReferenceId(orderId);
        try {
            transactionRepository.save(txn);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateTransactionException("Transaction already exists for ref=REDEMPTION/" + orderId);
        }

        log.info("Redeemed {} points for user={}", points, userId);
        outboxService.publish("Loyalty", account.getId().toString(), "PointsRedeemed", txn.getId());
        return toResponse(account);
    }

    void checkTierUpgrade(LoyaltyAccount account) {
        LoyaltyTier newTier = LoyaltyTier.fromLifetimePoints(account.getLifetimePoints());
        if (newTier.ordinal() > account.getTier().ordinal()) {
            log.info("Tier upgrade for user={}: {} -> {}", account.getUserId(), account.getTier(), newTier);
            account.setTier(newTier);
            accountRepository.save(account);
            outboxService.publish("Loyalty", account.getId().toString(), "TierUpgraded", newTier.name());
        }
    }

    private LoyaltyAccount createAccount(UUID userId) {
        LoyaltyAccount account = new LoyaltyAccount();
        account.setUserId(userId);
        return accountRepository.save(account);
    }

    private LoyaltyResponse toResponse(LoyaltyAccount account) {
        return new LoyaltyResponse(
            account.getPointsBalance(),
            account.getTier().name(),
            account.getLifetimePoints()
        );
    }
}
