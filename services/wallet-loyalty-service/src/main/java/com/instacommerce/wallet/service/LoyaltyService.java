package com.instacommerce.wallet.service;

import com.instacommerce.wallet.config.WalletProperties;
import com.instacommerce.wallet.domain.model.LoyaltyAccount;
import com.instacommerce.wallet.domain.model.LoyaltyTier;
import com.instacommerce.wallet.domain.model.LoyaltyTransaction;
import com.instacommerce.wallet.domain.model.WalletTransaction;
import com.instacommerce.wallet.dto.response.LoyaltyResponse;
import com.instacommerce.wallet.exception.ApiException;
import com.instacommerce.wallet.exception.DuplicateTransactionException;
import com.instacommerce.wallet.repository.LoyaltyAccountRepository;
import com.instacommerce.wallet.repository.LoyaltyTransactionRepository;
import jakarta.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final WalletService walletService;

    public LoyaltyService(LoyaltyAccountRepository accountRepository,
                          LoyaltyTransactionRepository transactionRepository,
                          WalletProperties walletProperties,
                          OutboxService outboxService) {
        this(accountRepository, transactionRepository, walletProperties, outboxService, null);
    }

    @Autowired
    public LoyaltyService(LoyaltyAccountRepository accountRepository,
                          LoyaltyTransactionRepository transactionRepository,
                          WalletProperties walletProperties,
                          OutboxService outboxService,
                          @Nullable WalletService walletService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.walletProperties = walletProperties;
        this.outboxService = outboxService;
        this.walletService = walletService;
    }

    @Transactional(readOnly = true)
    public LoyaltyResponse getBalance(UUID userId) {
        return accountRepository.findByUserId(userId)
            .map(this::toResponse)
            .orElseGet(() -> new LoyaltyResponse(0, LoyaltyTier.BRONZE.name(), 0));
    }

    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class,
               maxAttempts = 3, backoff = @Backoff(delay = 100, multiplier = 2))
    @Transactional
    public LoyaltyResponse earnPoints(UUID userId, String orderId, long orderTotalCents) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId must not be blank");
        }
        if (orderTotalCents < 0) {
            throw new IllegalArgumentException("orderTotalCents must not be negative");
        }
        String normalizedOrderId = orderId.trim();
        LoyaltyAccount account = findOrCreateAccount(userId);

        int pointsPerRupee = Math.max(0, walletProperties.getLoyalty().getPointsPerRupee());
        int pointsEarned = computeCappedEarnedPoints(orderTotalCents, pointsPerRupee);
        if (pointsEarned <= 0) {
            checkTierUpgrade(account);
            return toResponse(account);
        }

        account.setPointsBalance(saturatingAdd(account.getPointsBalance(), pointsEarned));
        account.setLifetimePoints(saturatingAdd(account.getLifetimePoints(), pointsEarned));
        accountRepository.save(account);

        LoyaltyTransaction txn = new LoyaltyTransaction();
        txn.setAccount(account);
        txn.setType(LoyaltyTransaction.Type.EARN);
        txn.setPoints(pointsEarned);
        txn.setReferenceType("ORDER");
        txn.setReferenceId(normalizedOrderId);
        try {
            txn = transactionRepository.saveAndFlush(txn);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateTransactionException("Transaction already exists for ref=ORDER/" + normalizedOrderId);
        }

        checkTierUpgrade(account);
        log.info("Earned {} points for user={} order={}", pointsEarned, userId, normalizedOrderId);

        outboxService.publish("Loyalty", account.getId().toString(), "PointsEarned",
            buildPointEventPayload(txn, account, userId, normalizedOrderId, pointsEarned, "ORDER"));
        return toResponse(account);
    }

    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class,
               maxAttempts = 3, backoff = @Backoff(delay = 100, multiplier = 2))
    @Transactional
    public LoyaltyResponse redeemPoints(UUID userId, int points, String orderId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        if (points <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_POINTS",
                "Points to redeem must be positive");
        }
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId must not be blank");
        }
        String normalizedOrderId = orderId.trim();

        LoyaltyAccount account = accountRepository.findByUserIdForUpdate(userId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "LOYALTY_ACCOUNT_NOT_FOUND",
                "Loyalty account not found for user: " + userId));

        if (account.getPointsBalance() < points) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INSUFFICIENT_POINTS",
                String.format("Insufficient points: available=%d, requested=%d", account.getPointsBalance(), points));
        }

        account.setPointsBalance(account.getPointsBalance() - points);
        accountRepository.save(account);

        LoyaltyTransaction txn = new LoyaltyTransaction();
        txn.setAccount(account);
        txn.setType(LoyaltyTransaction.Type.REDEEM);
        txn.setPoints(points);
        txn.setReferenceType("REDEMPTION");
        txn.setReferenceId(normalizedOrderId);
        try {
            txn = transactionRepository.saveAndFlush(txn);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateTransactionException("Transaction already exists for ref=REDEMPTION/" + normalizedOrderId);
        }

        long pointsToPaiseRate = walletProperties.getLoyalty().getPointsToPaiseRate();
        long walletCreditCents = (walletService != null && pointsToPaiseRate > 0)
            ? Math.multiplyExact((long) points, pointsToPaiseRate)
            : 0L;
        if (walletCreditCents > 0) {
            walletService.credit(
                userId,
                walletCreditCents,
                WalletTransaction.ReferenceType.PROMOTION,
                "loyalty-redeem-" + normalizedOrderId,
                "Loyalty points redemption (" + points + " pts)"
            );
        }

        Map<String, Object> eventPayload = buildPointEventPayload(
            txn, account, userId, normalizedOrderId, points, "REDEMPTION");
        eventPayload.put("walletCreditCents", walletCreditCents);
        log.info("Redeemed {} points for user={} walletCreditCents={}", points, userId, walletCreditCents);
        outboxService.publish("Loyalty", account.getId().toString(), "PointsRedeemed", eventPayload);
        return new LoyaltyResponse(
            account.getPointsBalance(),
            account.getTier().name(),
            account.getLifetimePoints(),
            walletCreditCents
        );
    }

    private int computeCappedEarnedPoints(long orderTotalCents, int pointsPerRupee) {
        if (orderTotalCents <= 0 || pointsPerRupee <= 0) {
            return 0;
        }
        int configuredCap = walletProperties.getLoyalty().getMaxPointsEarnPerOrder();
        int maxPoints = configuredCap > 0 ? configuredCap : Integer.MAX_VALUE;
        long quotient = orderTotalCents / 100L;
        long remainder = orderTotalCents % 100L;
        long fromQuotient = quotient > (long) maxPoints / pointsPerRupee
            ? maxPoints
            : quotient * pointsPerRupee;
        long fromRemainder = (remainder * pointsPerRupee) / 100L;
        long total = Math.min((long) maxPoints, fromQuotient + fromRemainder);
        return (int) Math.max(0L, total);
    }

    private int saturatingAdd(int current, int delta) {
        long sum = (long) current + (long) delta;
        return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }

    private Map<String, Object> buildPointEventPayload(LoyaltyTransaction txn, LoyaltyAccount account,
                                                       UUID userId, String orderId, int points,
                                                       String referenceType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transactionId", txn.getId().toString());
        payload.put("accountId", account.getId().toString());
        payload.put("userId", userId.toString());
        payload.put("orderId", orderId);
        payload.put("points", points);
        payload.put("tier", account.getTier().name());
        payload.put("totalBalance", account.getPointsBalance());
        payload.put("pointsBalance", account.getPointsBalance());
        payload.put("referenceType", referenceType);
        payload.put("referenceId", orderId);
        return payload;
    }

    void checkTierUpgrade(LoyaltyAccount account) {
        LoyaltyTier newTier = LoyaltyTier.fromLifetimePoints(account.getLifetimePoints());
        if (newTier.ordinal() > account.getTier().ordinal()) {
            LoyaltyTier previousTier = account.getTier();
            log.info("Tier upgrade for user={}: {} -> {}", account.getUserId(), previousTier, newTier);
            account.setTier(newTier);
            accountRepository.save(account);
            Map<String, Object> tierPayload = new LinkedHashMap<>();
            tierPayload.put("accountId", account.getId().toString());
            tierPayload.put("userId", account.getUserId().toString());
            tierPayload.put("previousTier", previousTier.name());
            tierPayload.put("tier", newTier.name());
            tierPayload.put("points", account.getPointsBalance());
            tierPayload.put("totalBalance", account.getPointsBalance());
            tierPayload.put("lifetimePoints", account.getLifetimePoints());
            outboxService.publish("Loyalty", account.getId().toString(), "TierUpgraded", tierPayload);
        }
    }

    private LoyaltyAccount findOrCreateAccount(UUID userId) {
        return accountRepository.findByUserIdForUpdate(userId)
            .orElseGet(() -> createAccount(userId));
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
