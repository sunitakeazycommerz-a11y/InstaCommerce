package com.instacommerce.pricing.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class StoreSurgeStateStore {

    private static final Logger log = LoggerFactory.getLogger(StoreSurgeStateStore.class);

    public static final Duration DEFAULT_TTL = Duration.ofMinutes(15);
    public static final int DEFAULT_MAX_ENTRIES = 2048;

    public static final BigDecimal DEFAULT_SURGE_MODE_MULTIPLIER = new BigDecimal("1.25");
    public static final BigDecimal HIGH_ETA_SURGE_MULTIPLIER = new BigDecimal("1.35");
    public static final BigDecimal DEFAULT_THROTTLED_MULTIPLIER = new BigDecimal("1.25");
    public static final BigDecimal HIGH_THROTTLED_MULTIPLIER = new BigDecimal("1.50");
    public static final BigDecimal DEFAULT_PAUSED_MULTIPLIER = new BigDecimal("1.60");

    public record StoreSurgeState(
            UUID storeId,
            BigDecimal surgeMultiplier,
            boolean surgeModeEnabled,
            boolean throttled,
            boolean paused,
            int etaExtensionMinutes,
            String reason,
            Instant updatedAt,
            Instant expiresAt
    ) {
        public boolean isSurgeActive() {
            return surgeModeEnabled
                    || throttled
                    || paused
                    || (surgeMultiplier != null && surgeMultiplier.compareTo(BigDecimal.ONE) > 0);
        }

        public boolean isExpired(Instant now) {
            return expiresAt != null && expiresAt.isBefore(now);
        }
    }

    private final Map<UUID, StoreSurgeState> states = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final int maxEntries;
    private final Clock clock;

    public StoreSurgeStateStore() {
        this(DEFAULT_TTL, DEFAULT_MAX_ENTRIES, Clock.systemUTC());
    }

    @Autowired
    public StoreSurgeStateStore(
            @Value("${pricing.surge.state-ttl:PT15M}") Duration ttl,
            @Value("${pricing.surge.max-stores:2048}") int maxEntries) {
        this(ttl, maxEntries, Clock.systemUTC());
    }

    public StoreSurgeStateStore(Duration ttl, int maxEntries, Clock clock) {
        this.ttl = (ttl != null && !ttl.isNegative() && !ttl.isZero()) ? ttl : DEFAULT_TTL;
        this.maxEntries = Math.max(1, maxEntries);
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public void recordSurgeModeChanged(
            UUID storeId,
            boolean surgeModeEnabled,
            BigDecimal explicitMultiplier,
            Integer etaExtensionMinutes) {
        if (storeId == null) {
            return;
        }
        if (!surgeModeEnabled && (explicitMultiplier == null || explicitMultiplier.compareTo(BigDecimal.ONE) <= 0)) {
            states.remove(storeId);
            log.info("Cleared live warehouse surge state for storeId={}", storeId);
            return;
        }

        int etaMinutes = etaExtensionMinutes != null ? Math.max(0, etaExtensionMinutes) : 0;
        BigDecimal fallbackMultiplier = etaMinutes >= 25
                ? HIGH_ETA_SURGE_MULTIPLIER
                : DEFAULT_SURGE_MODE_MULTIPLIER;
        BigDecimal multiplier = clampMultiplier(explicitMultiplier, fallbackMultiplier);
        Instant now = clock.instant();
        StoreSurgeState existing = getValidState(storeId, now);
        boolean throttled = existing != null && existing.throttled();
        boolean paused = existing != null && existing.paused();
        if (existing != null && existing.surgeMultiplier() != null && explicitMultiplier == null) {
            multiplier = multiplier.max(existing.surgeMultiplier());
        }

        StoreSurgeState updated = new StoreSurgeState(
                storeId,
                multiplier,
                true,
                throttled,
                paused,
                etaMinutes,
                "WAREHOUSE_SURGE_MODE",
                now,
                now.plus(ttl));
        putBounded(storeId, updated, now);
        log.info("Recorded warehouse surge mode for storeId={}: multiplier={}, etaExtensionMinutes={}",
                storeId, multiplier, etaMinutes);
    }

    public void recordCapacityThrottled(
            UUID storeId,
            Integer currentPending,
            Integer maxConcurrent,
            Boolean surgeMode,
            Integer etaExtensionMinutes,
            BigDecimal explicitMultiplier) {
        if (storeId == null) {
            return;
        }
        Instant now = clock.instant();
        boolean highUtilization = currentPending != null
                && maxConcurrent != null
                && maxConcurrent > 0
                && ((double) currentPending / maxConcurrent) >= 0.95;
        boolean surgeEnabled = Boolean.TRUE.equals(surgeMode);
        BigDecimal fallbackMultiplier = (surgeEnabled || highUtilization)
                ? HIGH_THROTTLED_MULTIPLIER
                : DEFAULT_THROTTLED_MULTIPLIER;
        BigDecimal multiplier = clampMultiplier(explicitMultiplier, fallbackMultiplier);

        StoreSurgeState existing = getValidState(storeId, now);
        if (existing != null && existing.surgeMultiplier() != null && explicitMultiplier == null) {
            multiplier = multiplier.max(existing.surgeMultiplier());
        }
        int etaMinutes = etaExtensionMinutes != null
                ? Math.max(0, etaExtensionMinutes)
                : (existing != null ? existing.etaExtensionMinutes() : 15);

        StoreSurgeState updated = new StoreSurgeState(
                storeId,
                multiplier,
                surgeEnabled || (existing != null && existing.surgeModeEnabled()),
                true,
                false,
                etaMinutes,
                "WAREHOUSE_CAPACITY_THROTTLED",
                now,
                now.plus(ttl));
        putBounded(storeId, updated, now);
        log.info("Recorded warehouse capacity throttle for storeId={}: multiplier={}, pending={}/{}",
                storeId, multiplier, currentPending, maxConcurrent);
    }

    public void recordCapacityPaused(
            UUID storeId,
            Integer currentPending,
            Integer maxConcurrent,
            BigDecimal explicitMultiplier) {
        if (storeId == null) {
            return;
        }
        Instant now = clock.instant();
        BigDecimal multiplier = clampMultiplier(explicitMultiplier, DEFAULT_PAUSED_MULTIPLIER);
        StoreSurgeState existing = getValidState(storeId, now);
        if (existing != null && existing.surgeMultiplier() != null && explicitMultiplier == null) {
            multiplier = multiplier.max(existing.surgeMultiplier());
        }
        int etaMinutes = existing != null ? existing.etaExtensionMinutes() : 20;

        StoreSurgeState updated = new StoreSurgeState(
                storeId,
                multiplier,
                existing != null && existing.surgeModeEnabled(),
                true,
                true,
                etaMinutes,
                "WAREHOUSE_CAPACITY_PAUSED",
                now,
                now.plus(ttl));
        putBounded(storeId, updated, now);
        log.info("Recorded warehouse capacity pause for storeId={}: multiplier={}, pending={}/{}",
                storeId, multiplier, currentPending, maxConcurrent);
    }

    public Optional<StoreSurgeState> getActiveState(UUID storeId) {
        if (storeId == null) {
            return Optional.empty();
        }
        StoreSurgeState state = getValidState(storeId, clock.instant());
        if (state == null || !state.isSurgeActive()) {
            return Optional.empty();
        }
        return Optional.of(state);
    }

    public BigDecimal resolveActiveMultiplier(UUID storeId) {
        return getActiveState(storeId)
                .map(StoreSurgeState::surgeMultiplier)
                .orElse(BigDecimal.ONE);
    }

    public void clear(UUID storeId) {
        if (storeId != null) {
            states.remove(storeId);
        }
    }

    public int size() {
        evictExpired(clock.instant());
        return states.size();
    }

    private StoreSurgeState getValidState(UUID storeId, Instant now) {
        StoreSurgeState state = states.get(storeId);
        if (state == null) {
            return null;
        }
        if (state.isExpired(now)) {
            states.remove(storeId, state);
            return null;
        }
        return state;
    }

    private void putBounded(UUID storeId, StoreSurgeState state, Instant now) {
        if (!states.containsKey(storeId) && states.size() >= maxEntries) {
            evictExpired(now);
            while (states.size() >= maxEntries) {
                Optional<Map.Entry<UUID, StoreSurgeState>> oldest = states.entrySet().stream()
                        .min(Comparator.comparing(entry -> entry.getValue().updatedAt()));
                if (oldest.isEmpty()) {
                    break;
                }
                states.remove(oldest.get().getKey(), oldest.get().getValue());
            }
        }
        states.put(storeId, state);
    }

    private void evictExpired(Instant now) {
        states.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
    }

    private static BigDecimal clampMultiplier(BigDecimal candidate, BigDecimal fallback) {
        BigDecimal effective = (candidate != null && candidate.compareTo(BigDecimal.ONE) > 0)
                ? candidate
                : fallback;
        if (effective.compareTo(BigDecimal.ONE) < 0) {
            return BigDecimal.ONE;
        }
        if (effective.compareTo(SurgePricingService.MAX_SURGE_MULTIPLIER) > 0) {
            return SurgePricingService.MAX_SURGE_MULTIPLIER;
        }
        return effective;
    }
}
