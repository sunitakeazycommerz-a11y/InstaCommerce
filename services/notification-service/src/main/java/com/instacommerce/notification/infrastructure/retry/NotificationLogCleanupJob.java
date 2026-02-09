package com.instacommerce.notification.infrastructure.retry;

import com.instacommerce.notification.repository.NotificationLogRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes notification logs older than 90 days per data retention policy.
 */
@Component
public class NotificationLogCleanupJob {
    private static final Logger logger = LoggerFactory.getLogger(NotificationLogCleanupJob.class);
    private static final int RETENTION_DAYS = 90;

    private final NotificationLogRepository logRepository;

    public NotificationLogCleanupJob(NotificationLogRepository logRepository) {
        this.logRepository = logRepository;
    }

    @Scheduled(cron = "${notification.cleanup.cron:0 0 3 * * *}")
    @Transactional
    public void cleanupOldLogs() {
        Instant cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);
        int deleted = logRepository.deleteOlderThan(cutoff);
        if (deleted > 0) {
            logger.info("Deleted {} notification logs older than {} days", deleted, RETENTION_DAYS);
        }
    }
}
