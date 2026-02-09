package com.instacommerce.order.service;

import com.instacommerce.order.repository.OutboxEventRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(OutboxCleanupJob.class);

    private final OutboxEventRepository outboxEventRepository;

    public OutboxCleanupJob(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    @Scheduled(cron = "0 0 3 * * *")
    @SchedulerLock(name = "order-outbox-cleanup", lockAtMostFor = "30m", lockAtLeastFor = "5m")
    @Transactional
    public void cleanupSentEvents() {
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        int deleted = outboxEventRepository.deleteSentEventsBefore(cutoff);
        log.info("Outbox cleanup: deleted {} sent events older than {}", deleted, cutoff);
    }
}
