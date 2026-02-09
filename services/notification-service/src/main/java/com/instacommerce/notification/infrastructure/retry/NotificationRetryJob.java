package com.instacommerce.notification.infrastructure.retry;

import com.instacommerce.notification.domain.model.NotificationLog;
import com.instacommerce.notification.domain.model.NotificationStatus;
import com.instacommerce.notification.repository.NotificationLogRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls for RETRY_PENDING notification logs whose nextRetryAt has passed,
 * and retries sending them using exponential backoff.
 */
@Component
public class NotificationRetryJob {
    private static final Logger logger = LoggerFactory.getLogger(NotificationRetryJob.class);

    private final NotificationLogRepository logRepository;
    private final RetryableNotificationSender sender;

    public NotificationRetryJob(NotificationLogRepository logRepository,
                                RetryableNotificationSender sender) {
        this.logRepository = logRepository;
        this.sender = sender;
    }

    @Scheduled(fixedDelayString = "${notification.retry.poll-interval-ms:5000}")
    public void retryPendingNotifications() {
        List<NotificationLog> pending = logRepository.findByStatusAndNextRetryAtLessThanEqual(
            NotificationStatus.RETRY_PENDING, Instant.now());
        if (pending.isEmpty()) {
            return;
        }
        logger.info("Retrying {} pending notifications", pending.size());
        for (NotificationLog log : pending) {
            try {
                sender.attemptSend(log.getChannel().name(), log.getRecipient(), log, null);
            } catch (Exception ex) {
                logger.error("Unexpected error retrying notification {}", log.getId(), ex);
            }
        }
    }
}
