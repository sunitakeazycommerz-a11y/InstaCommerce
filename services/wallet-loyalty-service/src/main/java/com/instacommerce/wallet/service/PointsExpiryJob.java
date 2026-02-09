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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PointsExpiryJob {
    private static final Logger log = LoggerFactory.getLogger(PointsExpiryJob.class);

    private final LoyaltyAccountRepository accountRepository;
    private final LoyaltyTransactionRepository transactionRepository;
    private final WalletProperties walletProperties;

    public PointsExpiryJob(LoyaltyAccountRepository accountRepository,
                           LoyaltyTransactionRepository transactionRepository,
                           WalletProperties walletProperties) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.walletProperties = walletProperties;
    }

    @Scheduled(cron = "0 0 2 * * *")
    @SchedulerLock(name = "points-expiry", lockAtLeastFor = "PT5M", lockAtMostFor = "PT30M")
    @Transactional
    public void expireOldPoints() {
        int expiryMonths = walletProperties.getLoyalty().getPointsExpiryMonths();
        Instant cutoff = Instant.now().minus(expiryMonths * 30L, ChronoUnit.DAYS);
        log.info("Running points expiry job, cutoff={}", cutoff);

        List<LoyaltyAccount> accounts = accountRepository.findAll();
        int totalExpired = 0;

        for (LoyaltyAccount account : accounts) {
            int expirablePoints = transactionRepository.sumExpirablePoints(account.getId(), cutoff);
            if (expirablePoints > 0) {
                int pointsToExpire = Math.min(expirablePoints, account.getPointsBalance());
                if (pointsToExpire > 0) {
                    account.setPointsBalance(account.getPointsBalance() - pointsToExpire);
                    accountRepository.save(account);

                    LoyaltyTransaction txn = new LoyaltyTransaction();
                    txn.setAccount(account);
                    txn.setType(LoyaltyTransaction.Type.EXPIRE);
                    txn.setPoints(pointsToExpire);
                    txn.setReferenceType("EXPIRY");
                    txn.setReferenceId("expiry-" + UUID.randomUUID());
                    transactionRepository.save(txn);

                    totalExpired += pointsToExpire;
                }
            }
        }
        log.info("Points expiry complete: expired {} points across accounts", totalExpired);
    }
}
