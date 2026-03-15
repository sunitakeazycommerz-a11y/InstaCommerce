package com.instacommerce.wallet.service;

import com.instacommerce.wallet.config.WalletProperties;
import com.instacommerce.wallet.domain.model.LoyaltyAccount;
import com.instacommerce.wallet.domain.model.LoyaltyTransaction;
import com.instacommerce.wallet.repository.LoyaltyAccountRepository;
import com.instacommerce.wallet.repository.LoyaltyTransactionRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class PointsExpiryJob {
    private static final Logger log = LoggerFactory.getLogger(PointsExpiryJob.class);
    private static final int ACCOUNT_BATCH_SIZE = 500;
    private static final int TRANSACTION_BATCH_SIZE = 500;

    private final LoyaltyAccountRepository accountRepository;
    private final LoyaltyTransactionRepository transactionRepository;
    private final WalletProperties walletProperties;
    private final TransactionTemplate transactionTemplate;

    public PointsExpiryJob(LoyaltyAccountRepository accountRepository,
                           LoyaltyTransactionRepository transactionRepository,
                           WalletProperties walletProperties,
                           PlatformTransactionManager transactionManager) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.walletProperties = walletProperties;
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactionTemplate = template;
    }

    @Scheduled(cron = "0 0 2 * * *")
    @SchedulerLock(name = "points-expiry", lockAtLeastFor = "PT5M", lockAtMostFor = "PT2H")
    public void expireOldPoints() {
        int expiryMonths = walletProperties.getLoyalty().getPointsExpiryMonths();
        Instant cutoff = Instant.now().minus(expiryMonths * 30L, ChronoUnit.DAYS);
        log.info("Running points expiry job, cutoff={}", cutoff);

        int totalExpired = 0;
        int page = 0;
        Slice<LoyaltyAccount> batch;
        do {
            batch = accountRepository.findAllBy(PageRequest.of(page, ACCOUNT_BATCH_SIZE));
            for (LoyaltyAccount account : batch) {
                Integer expired = transactionTemplate.execute(
                    status -> expireAccountPoints(account.getId(), cutoff));
                totalExpired += expired == null ? 0 : expired;
            }
            page++;
        } while (batch.hasNext());
        log.info("Points expiry complete: expired {} points across accounts", totalExpired);
    }

    int expireAccountPoints(UUID accountId, Instant cutoff) {
        LoyaltyAccount account = accountRepository.findById(accountId).orElse(null);
        if (account == null) {
            return 0;
        }

        int remainingBalance = account.getPointsBalance();
        if (remainingBalance <= 0) {
            return 0;
        }

        int totalExpired = 0;
        while (remainingBalance > 0) {
            List<LoyaltyTransaction> expirable = transactionRepository.findExpirableEarnTransactions(
                accountId, cutoff, PageRequest.of(0, TRANSACTION_BATCH_SIZE));
            if (expirable.isEmpty()) {
                break;
            }

            for (LoyaltyTransaction earnTxn : expirable) {
                if (remainingBalance <= 0) {
                    break;
                }

                int pointsToExpire = Math.min(earnTxn.getPoints(), remainingBalance);
                if (pointsToExpire <= 0) {
                    continue;
                }

                LoyaltyTransaction expiryTxn = new LoyaltyTransaction();
                expiryTxn.setAccount(account);
                expiryTxn.setType(LoyaltyTransaction.Type.EXPIRE);
                expiryTxn.setPoints(pointsToExpire);
                expiryTxn.setReferenceType("EXPIRY");
                expiryTxn.setReferenceId(earnTxn.getId().toString());
                transactionRepository.save(expiryTxn);

                remainingBalance -= pointsToExpire;
                totalExpired += pointsToExpire;
            }
        }

        if (totalExpired > 0) {
            account.setPointsBalance(remainingBalance);
            accountRepository.save(account);
        }

        return totalExpired;
    }
}
