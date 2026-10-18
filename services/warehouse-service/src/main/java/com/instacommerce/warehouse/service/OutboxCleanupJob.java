package com.instacommerce.warehouse.service;

import com.instacommerce.warehouse.repository.OutboxEventRepository;
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
    private static final int BATCH_SIZE = 1000;

    private final OutboxEventRepository outboxEventRepository;

    public OutboxCleanupJob(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    @Scheduled(cron = "${outbox.cleanup.cron:0 0 3 * * *}")
    @SchedulerLock(name = "warehouse-outbox-cleanup", lockAtMostFor = "30m", lockAtLeastFor = "1m")
    @Transactional
    public void cleanupSentEvents() {
        Instant cutoff = Instant.now().minus(RETENTION);
        int totalDeleted = 0;
        int deleted;
        do {
            deleted = outboxEventRepository.deleteBatchSentEventsBefore(cutoff);
            totalDeleted += deleted;
        } while (deleted >= BATCH_SIZE);
        log.info("Outbox cleanup: deleted {} sent events older than 7 days", totalDeleted);
    }
}
