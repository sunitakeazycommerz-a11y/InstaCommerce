package com.instacommerce.cart.service;

import com.instacommerce.cart.repository.CartRepository;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CartCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(CartCleanupJob.class);

    private final CartRepository cartRepository;
    private final CacheManager cacheManager;

    public CartCleanupJob(CartRepository cartRepository,
                          CacheManager cacheManager) {
        this.cartRepository = cartRepository;
        this.cacheManager = cacheManager;
    }

    @Scheduled(cron = "0 */15 * * * *")
    @SchedulerLock(name = "cartCleanup", lockAtLeastFor = "PT5M", lockAtMostFor = "PT14M")
    @Transactional
    public void deleteExpiredCarts() {
        int deleted = cartRepository.deleteExpiredCarts(Instant.now());
        if (deleted > 0) {
            Cache cache = cacheManager.getCache("carts");
            if (cache != null) {
                cache.clear();
            }
        }
        log.info("Cart cleanup: deleted {} expired carts", deleted);
    }
}
