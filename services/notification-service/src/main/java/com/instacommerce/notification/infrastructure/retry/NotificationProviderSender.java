package com.instacommerce.notification.infrastructure.retry;

import com.instacommerce.notification.domain.model.NotificationLog;
import com.instacommerce.notification.domain.model.NotificationStatus;
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
import org.springframework.stereotype.Component;

@Component
public class NotificationProviderSender {
    private static final Logger logger = LoggerFactory.getLogger(NotificationProviderSender.class);
    static final int MAX_RETRIES = 3;
    static final Duration[] BACKOFF = {
        Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(5)
    };

    private final NotificationProviderRegistry providerRegistry;
    private final NotificationLogRepository logRepository;
    private final NotificationMetrics metrics;

    public NotificationProviderSender(NotificationProviderRegistry providerRegistry,
                                      NotificationLogRepository logRepository,
                                      NotificationMetrics metrics) {
        this.providerRegistry = providerRegistry;
        this.logRepository = logRepository;
        this.metrics = metrics;
    }

    public DispatchResult dispatch(NotificationLog log, String recipient) {
        Optional<NotificationProvider> providerOpt = providerRegistry.findProvider(log.getChannel());
        if (providerOpt.isEmpty()) {
            log.setStatus(NotificationStatus.SKIPPED);
            log.setLastError("No provider configured");
            logRepository.save(log);
            metrics.incrementSkipped();
            return new DispatchResult(DispatchStatus.FALLBACK_REQUIRED, null);
        }
        NotificationProvider provider = providerOpt.get();
        String masked = MaskingUtil.maskRecipient(recipient, log.getChannel());
        try {
            String providerRef = provider.send(new NotificationSendRequest(
                log.getChannel(), recipient, log.getSubject(), log.getBody()));
            log.setStatus(NotificationStatus.SENT);
            log.setProviderRef(providerRef);
            log.setSentAt(Instant.now());
            log.setAttempts(log.getRetryCount() + 1);
            logRepository.save(log);
            metrics.incrementSent();
            return new DispatchResult(DispatchStatus.SENT, null);
        } catch (ProviderTemporaryException ex) {
            int currentRetry = log.getRetryCount();
            log.setAttempts(currentRetry + 1);
            log.setLastError(ex.getMessage());
            if (currentRetry + 1 >= MAX_RETRIES) {
                log.setStatus(NotificationStatus.FAILED);
                logRepository.save(log);
                metrics.incrementFailed();
                return new DispatchResult(DispatchStatus.FALLBACK_REQUIRED, "Retries exhausted");
            }
            Duration backoff = BACKOFF[Math.min(currentRetry, BACKOFF.length - 1)];
            log.setRetryCount(currentRetry + 1);
            log.setNextRetryAt(Instant.now().plus(backoff));
            log.setStatus(NotificationStatus.RETRY_PENDING);
            logRepository.save(log);
            logger.warn("Temporary notification failure for {} attempt {}, scheduled retry at {}",
                masked, currentRetry + 1, log.getNextRetryAt());
            return new DispatchResult(DispatchStatus.RETRY_SCHEDULED, null);
        } catch (ProviderPermanentException ex) {
            markPermanentFailure(log, ex.getMessage());
            return new DispatchResult(DispatchStatus.FALLBACK_REQUIRED, ex.getMessage());
        }
    }

    public void markPermanentFailure(NotificationLog log, String reason) {
        log.setStatus(NotificationStatus.FAILED);
        log.setLastError(reason);
        log.setAttempts(log.getRetryCount() + 1);
        logRepository.save(log);
        metrics.incrementFailed();
    }

    public enum DispatchStatus {
        SENT,
        RETRY_SCHEDULED,
        FALLBACK_REQUIRED
    }

    public record DispatchResult(DispatchStatus status, String dlqReason) {
    }
}
