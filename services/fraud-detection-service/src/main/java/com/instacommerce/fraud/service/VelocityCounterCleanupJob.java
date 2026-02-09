package com.instacommerce.fraud.service;

import com.instacommerce.fraud.repository.VelocityCounterRepository;
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
public class VelocityCounterCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(VelocityCounterCleanupJob.class);
    private final VelocityCounterRepository velocityCounterRepository;
    private final Duration retention;

    public VelocityCounterCleanupJob(VelocityCounterRepository velocityCounterRepository,
                                     @Value("${fraud.velocity.retention:2d}") Duration retention) {
        this.velocityCounterRepository = velocityCounterRepository;
        this.retention = retention;
    }

    @Scheduled(cron = "${fraud.velocity.cleanup-cron:0 15 * * * *}")
    @SchedulerLock(name = "velocityCounterCleanup", lockAtLeastFor = "PT5M", lockAtMostFor = "PT30M")
    @Transactional
    public void purgeExpiredCounters() {
        Instant cutoff = Instant.now().minus(retention);
        int deleted = velocityCounterRepository.deleteByWindowEndBefore(cutoff);
        log.info("Velocity counter cleanup: deleted {} counters older than {}", deleted, cutoff);
    }
}
