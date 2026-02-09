package com.instacommerce.cart.service;

import com.instacommerce.cart.repository.OutboxEventRepository;
import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(OutboxCleanupJob.class);
    private static final Duration RETENTION = Duration.ofDays(7);

    private final OutboxEventRepository outboxEventRepository;

    public OutboxCleanupJob(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    @Scheduled(cron = "0 0 */6 * * *")
    @SchedulerLock(name = "outbox-cleanup", lockAtLeastFor = "PT5M", lockAtMostFor = "PT30M")
    @Transactional
    public void cleanupProcessedEvents() {
        Instant cutoff = Instant.now().minus(RETENTION);
        int deleted = outboxEventRepository.deleteSentEventsBefore(cutoff);
        log.info("Cleaned up {} processed outbox events older than 7 days", deleted);
    }
}
