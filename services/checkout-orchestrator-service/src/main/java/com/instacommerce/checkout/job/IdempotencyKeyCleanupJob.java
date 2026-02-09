package com.instacommerce.checkout.job;

import com.instacommerce.checkout.repository.CheckoutIdempotencyKeyRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class IdempotencyKeyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyCleanupJob.class);

    private final CheckoutIdempotencyKeyRepository repository;

    public IdempotencyKeyCleanupJob(CheckoutIdempotencyKeyRepository repository) {
        this.repository = repository;
    }

    @Scheduled(fixedRate = 3600_000) // every hour
    @SchedulerLock(name = "idempotencyKeyCleanup", lockAtLeastFor = "PT5M", lockAtMostFor = "PT30M")
    @Transactional
    public void cleanupExpiredKeys() {
        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        int deleted = repository.deleteExpiredKeys(cutoff);
        log.info("Cleaned up {} expired idempotency keys older than {}", deleted, cutoff);
    }
}
