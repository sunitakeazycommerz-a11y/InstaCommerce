package com.instacommerce.cart.service;

import com.instacommerce.cart.repository.CartRepository;
import com.instacommerce.cart.repository.OutboxEventRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CartCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(CartCleanupJob.class);

    private final CartRepository cartRepository;
    private final OutboxEventRepository outboxEventRepository;

    public CartCleanupJob(CartRepository cartRepository, OutboxEventRepository outboxEventRepository) {
        this.cartRepository = cartRepository;
        this.outboxEventRepository = outboxEventRepository;
    }

    @Scheduled(cron = "0 */15 * * * *")
    @SchedulerLock(name = "cartCleanup", lockAtLeastFor = "PT5M", lockAtMostFor = "PT14M")
    @Transactional
    public void deleteExpiredCarts() {
        int deleted = cartRepository.deleteExpiredCarts(Instant.now());
        log.info("Cart cleanup: deleted {} expired carts", deleted);
    }

    @Scheduled(cron = "0 0 3 * * *")
    @SchedulerLock(name = "outboxCleanup", lockAtLeastFor = "PT5M", lockAtMostFor = "PT30M")
    @Transactional
    public void cleanupSentEvents() {
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        int deleted = outboxEventRepository.deleteSentEventsBefore(cutoff);
        log.info("Outbox cleanup: deleted {} sent events older than {}", deleted, cutoff);
    }
}
