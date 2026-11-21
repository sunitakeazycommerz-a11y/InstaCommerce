package com.instacommerce.pricing.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

/**
 * Manages atomic reservation locks for promotions and coupons ({@code SET promo:{code}:{userId} 1 NX EX 300}).
 * Uses Redis when available and falls back to an in-memory lock map only when no Redis bean or connection is configured.
 */
@Service
public class PromoReservationService {

    private static final Logger log = LoggerFactory.getLogger(PromoReservationService.class);
    private static final long DEFAULT_LOCK_TTL_SECONDS = 300;
    private static final int PRUNE_THRESHOLD = 1024;

    private record ReservationEntry(Instant expiresAt) {
        boolean isExpired(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }

    private final StringRedisTemplate redisTemplate;
    private final Map<String, ReservationEntry> activeLocks = new ConcurrentHashMap<>();

    @Autowired
    public PromoReservationService(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.redisTemplate = redisTemplateProvider != null ? redisTemplateProvider.getIfAvailable() : null;
    }

    public boolean tryAcquire(String code, UUID userId, long ttlSeconds) {
        long effectiveTtl = ttlSeconds > 0 ? ttlSeconds : DEFAULT_LOCK_TTL_SECONDS;
        String key = buildKey(code, userId);

        if (redisTemplate != null) {
            ValueOperations<String, String> ops = redisTemplate.opsForValue();
            if (ops != null) {
                try {
                    Boolean acquired = ops.setIfAbsent(key, "1", Duration.ofSeconds(effectiveTtl));
                    boolean success = Boolean.TRUE.equals(acquired);
                    if (success) {
                        log.debug("Acquired Redis promo reservation lock for key={}", key);
                    } else {
                        log.warn("Promo reservation lock collision in Redis for key={}", key);
                    }
                    return success;
                } catch (DataAccessException ex) {
                    log.warn("Redis unavailable during promo reservation acquire for key={}, falling back to local lock", key, ex);
                }
            }
        }

        return tryAcquireLocally(key, effectiveTtl);
    }

    public boolean tryAcquire(String code, UUID userId) {
        return tryAcquire(code, userId, DEFAULT_LOCK_TTL_SECONDS);
    }

    public void release(String code, UUID userId) {
        String key = buildKey(code, userId);
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(key);
            } catch (DataAccessException ex) {
                log.warn("Redis unavailable during promo reservation release for key={}", key, ex);
            }
        }
        activeLocks.remove(key);
        log.debug("Released promo reservation lock for key={}", key);
    }

    private boolean tryAcquireLocally(String key, long ttlSeconds) {
        Instant now = Instant.now();
        if (activeLocks.size() >= PRUNE_THRESHOLD) {
            activeLocks.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
        }
        Instant expiry = now.plusSeconds(ttlSeconds);

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

    private String buildKey(String code, UUID userId) {
        return "promo:" + (code != null ? code.trim().toUpperCase(Locale.ROOT) : "NULL") + ":" + userId;
    }
}
