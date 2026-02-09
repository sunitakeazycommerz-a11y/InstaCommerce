package com.instacommerce.inventory.service;

import com.instacommerce.inventory.repository.OutboxEventRepository;
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
    private static final Duration RETENTION = Duration.ofDays(30);

    private final OutboxEventRepository outboxEventRepository;

    public OutboxCleanupJob(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    @Scheduled(cron = "${outbox.cleanup-cron:0 0 4 * * *}")
    @SchedulerLock(name = "outboxCleanup", lockAtLeastFor = "30s", lockAtMostFor = "5m")
    @Transactional
    public void purgeOldSentEvents() {
        Instant cutoff = Instant.now().minus(RETENTION);
        int deleted = outboxEventRepository.deleteSentBefore(cutoff);
        log.info("Outbox cleanup: deleted {} sent events older than 30 days", deleted);
    }
}
