package com.instacommerce.pricing.service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Manages atomic reservation locks for promotions and coupons:
 * Pattern: SET promo:{code}:{userId} 1 NX EX 300
 *
 * Guarantees that concurrent checkout attempts by automated syndicates or parallel
 * scripts cannot simultaneously exploit single-use or per-user coupons.
 */
@Service
public class PromoReservationService {

    private static final Logger log = LoggerFactory.getLogger(PromoReservationService.class);
    private static final long DEFAULT_LOCK_TTL_SECONDS = 300; // 5 minutes
    private static final int PRUNE_THRESHOLD = 1024;

    private record ReservationEntry(Instant expiresAt) {
        boolean isExpired(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }

    private final Map<String, ReservationEntry> activeLocks = new ConcurrentHashMap<>();

    public boolean tryAcquire(String code, UUID userId, long ttlSeconds) {
        Instant now = Instant.now();
        if (activeLocks.size() >= PRUNE_THRESHOLD) {
            activeLocks.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
        }
        String key = buildKey(code, userId);
        Instant expiry = now.plusSeconds(ttlSeconds > 0 ? ttlSeconds : DEFAULT_LOCK_TTL_SECONDS);

        final boolean[] acquired = new boolean[1];
        activeLocks.compute(key, (k, existing) -> {
            if (existing == null || existing.isExpired(now)) {
                acquired[0] = true;
                return new ReservationEntry(expiry);
            }
            acquired[0] = false;
            return existing;
        });

        if (acquired[0]) {
            log.debug("Acquired promo reservation lock for key={}", key);
        } else {
            log.warn("Promo reservation lock collision for key={}", key);
        }
        return acquired[0];
    }

    public boolean tryAcquire(String code, UUID userId) {
        return tryAcquire(code, userId, DEFAULT_LOCK_TTL_SECONDS);
    }

    /**
     * Releases an existing reservation lock.
     */
    public void release(String code, UUID userId) {
        String key = buildKey(code, userId);
        activeLocks.remove(key);
        log.debug("Released promo reservation lock for key={}", key);
    }

    private String buildKey(String code, UUID userId) {
        return "promo:" + (code != null ? code.trim().toUpperCase() : "NULL") + ":" + userId;
    }
}
