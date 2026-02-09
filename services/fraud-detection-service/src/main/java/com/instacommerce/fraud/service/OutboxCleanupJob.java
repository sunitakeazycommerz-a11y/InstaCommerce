package com.instacommerce.fraud.service;

import com.instacommerce.fraud.repository.OutboxEventRepository;
import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanupJob.class);
    private final OutboxEventRepository outboxEventRepository;
    private final Duration retention;

    public OutboxCleanupJob(OutboxEventRepository outboxEventRepository,
                            @Value("${outbox.retention:30d}") Duration retention) {
        this.outboxEventRepository = outboxEventRepository;
        this.retention = retention;
    }

    @Scheduled(cron = "${outbox.cleanup-cron:0 30 3 * * *}")
    @SchedulerLock(name = "outboxCleanup", lockAtLeastFor = "PT5M", lockAtMostFor = "PT30M")
    @Transactional
    public void purgeOldEvents() {
        Instant cutoff = Instant.now().minus(retention);
        int deleted = outboxEventRepository.deleteSentEventsBefore(cutoff);
        log.info("Outbox cleanup: deleted {} events older than {}", deleted, cutoff);
    }
}
