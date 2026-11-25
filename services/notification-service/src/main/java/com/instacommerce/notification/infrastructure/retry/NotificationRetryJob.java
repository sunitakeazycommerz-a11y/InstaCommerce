package com.instacommerce.notification.infrastructure.retry;

import com.instacommerce.notification.config.NotificationProperties;
import com.instacommerce.notification.domain.model.NotificationLog;
import com.instacommerce.notification.repository.NotificationLogRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polls for RETRY_PENDING notification logs whose nextRetryAt has passed,
 * claims them in a short transaction, and dispatches provider HTTP calls outside any transaction.
 */
@Component
public class NotificationRetryJob {
    private static final Logger logger = LoggerFactory.getLogger(NotificationRetryJob.class);
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(5);

    private final NotificationLogRepository logRepository;
    private final RetryableNotificationSender sender;
    private final int batchSize;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public NotificationRetryJob(NotificationLogRepository logRepository,
                                RetryableNotificationSender sender,
                                NotificationProperties notificationProperties) {
        this(logRepository, sender, notificationProperties, null, null);
    }

    @Autowired
    public NotificationRetryJob(NotificationLogRepository logRepository,
                                RetryableNotificationSender sender,
                                NotificationProperties notificationProperties,
                                ObjectProvider<PlatformTransactionManager> transactionManagerProvider,
                                ObjectProvider<Clock> clockProvider) {
        this.logRepository = logRepository;
        this.sender = sender;
        this.batchSize = Math.max(notificationProperties.getRetry().getBatchSize(), 1);
        PlatformTransactionManager txManager = transactionManagerProvider != null
            ? transactionManagerProvider.getIfAvailable()
            : null;
        this.transactionTemplate = txManager != null ? new TransactionTemplate(txManager) : null;
        Clock resolvedClock = clockProvider != null ? clockProvider.getIfAvailable() : null;
        this.clock = resolvedClock != null ? resolvedClock : Clock.systemUTC();
    }

    @Scheduled(fixedDelayString = "${notification.retry.poll-interval-ms:5000}")
    @SchedulerLock(name = "notification_retry_job", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1S")
    public void retryPendingNotifications() {
        List<NotificationLog> claimed = transactionTemplate != null
            ? transactionTemplate.execute(status -> claimPendingBatch())
            : claimPendingBatch();
        if (claimed == null || claimed.isEmpty()) {
            return;
        }
        logger.info("Retrying {} pending notifications", claimed.size());
        for (NotificationLog log : claimed) {
            try {
                sender.retry(log);
            } catch (Exception ex) {
                logger.error("Unexpected error retrying notification {}", log.getId(), ex);
            }
        }
    }

    private List<NotificationLog> claimPendingBatch() {
        Instant now = Instant.now(clock);
        List<NotificationLog> pending = logRepository.findPendingForRetry(now, batchSize);
        if (pending.isEmpty()) {
            return List.of();
        }
        Instant leaseUntil = now.plus(CLAIM_LEASE);
        for (NotificationLog log : pending) {
            log.setNextRetryAt(leaseUntil);
        }
        logRepository.saveAll(pending);
        return pending;
    }
}
