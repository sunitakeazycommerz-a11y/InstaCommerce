package com.instacommerce.notification.infrastructure.retry;

import com.instacommerce.notification.domain.model.NotificationLog;
import com.instacommerce.notification.domain.model.NotificationStatus;
import com.instacommerce.notification.dto.NotificationRequest;
import com.instacommerce.notification.infrastructure.metrics.NotificationMetrics;
import com.instacommerce.notification.provider.NotificationProvider;
import com.instacommerce.notification.provider.NotificationProviderRegistry;
import com.instacommerce.notification.provider.NotificationSendRequest;
import com.instacommerce.notification.provider.ProviderPermanentException;
import com.instacommerce.notification.provider.ProviderTemporaryException;
import com.instacommerce.notification.repository.NotificationLogRepository;
import com.instacommerce.notification.service.MaskingUtil;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class RetryableNotificationSender {
    private static final Logger logger = LoggerFactory.getLogger(RetryableNotificationSender.class);
    static final int MAX_RETRIES = 3;
    static final Duration[] BACKOFF = {
        Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(5)
    };

    private final NotificationProviderRegistry providerRegistry;
    private final NotificationLogRepository logRepository;
    private final NotificationDlqPublisher dlqPublisher;
    private final NotificationMetrics metrics;

    public RetryableNotificationSender(NotificationProviderRegistry providerRegistry,
                                       NotificationLogRepository logRepository,
                                       NotificationDlqPublisher dlqPublisher,
                                       NotificationMetrics metrics) {
        this.providerRegistry = providerRegistry;
        this.logRepository = logRepository;
        this.dlqPublisher = dlqPublisher;
        this.metrics = metrics;
    }

    @Async("notificationExecutor")
    public void send(NotificationRequest request, NotificationLog log, String subject, String body) {
        log.setEventType(request.eventType());
        log.setSubject(subject);
        log.setBody(body);
        attemptSend(request.channel().name(), request.recipient(), log, request);
    }

    /**
     * Attempt to send a notification. On temporary failure, schedule a DB-based retry
     * instead of blocking the thread with Thread.sleep.
     */
    void attemptSend(String channelName, String recipient, NotificationLog log, NotificationRequest request) {
        Optional<NotificationProvider> providerOpt = providerRegistry.findProvider(log.getChannel());
        if (providerOpt.isEmpty()) {
            log.setStatus(NotificationStatus.SKIPPED);
            log.setLastError("No provider configured");
            logRepository.save(log);
            metrics.incrementSkipped();
            return;
        }
        NotificationProvider provider = providerOpt.get();
        String masked = MaskingUtil.maskRecipient(recipient);
        try {
            String providerRef = provider.send(new NotificationSendRequest(
                log.getChannel(), recipient, log.getSubject(), log.getBody()));
            log.setStatus(NotificationStatus.SENT);
            log.setProviderRef(providerRef);
            log.setSentAt(Instant.now());
            log.setAttempts(log.getRetryCount() + 1);
            logRepository.save(log);
            metrics.incrementSent();
        } catch (ProviderTemporaryException ex) {
            int currentRetry = log.getRetryCount();
            log.setAttempts(currentRetry + 1);
            log.setLastError(ex.getMessage());
            if (currentRetry + 1 >= MAX_RETRIES) {
                log.setStatus(NotificationStatus.FAILED);
                logRepository.save(log);
                metrics.incrementFailed();
                if (request != null) {
                    dlqPublisher.publish(request, "Retries exhausted");
                }
                return;
            }
            Duration backoff = BACKOFF[Math.min(currentRetry, BACKOFF.length - 1)];
            log.setRetryCount(currentRetry + 1);
            log.setNextRetryAt(Instant.now().plus(backoff));
            log.setStatus(NotificationStatus.RETRY_PENDING);
            logRepository.save(log);
            logger.warn("Temporary notification failure for {} attempt {}, scheduled retry at {}",
                masked, currentRetry + 1, log.getNextRetryAt());
        } catch (ProviderPermanentException ex) {
            log.setStatus(NotificationStatus.FAILED);
            log.setLastError(ex.getMessage());
            log.setAttempts(log.getRetryCount() + 1);
            logRepository.save(log);
            metrics.incrementFailed();
            if (request != null) {
                dlqPublisher.publish(request, ex.getMessage());
            }
        }
    }
}
