package com.instacommerce.featureflag.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.CacheManager;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "feature-flag.cache.redis-enabled", havingValue = "true", matchIfMissing = true)
public class FlagCacheInvalidator implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(FlagCacheInvalidator.class);
    private static final long REDIS_TIMEOUT_MS = 5000L;

    private final RedisMessageListenerContainer listenerContainer;
    private final CacheManager cacheManager;
    private final ObjectMapper objectMapper;
    private final Counter invalidationCounter;
    private final Counter redisFailureCounter;
    private final AtomicLong maxStalenessMs;
    private final AtomicBoolean circuitBreakerOpen;

    public FlagCacheInvalidator(
            RedisMessageListenerContainer listenerContainer,
            CacheManager cacheManager,
            MeterRegistry meterRegistry) {
        this.listenerContainer = listenerContainer;
        this.cacheManager = cacheManager;
        this.objectMapper = new ObjectMapper();
        this.circuitBreakerOpen = new AtomicBoolean(false);
        this.maxStalenessMs = new AtomicLong(0);

        // Metrics
        this.invalidationCounter = Counter.builder("feature_flag.cache.invalidations")
                .description("Total cache invalidations received from Redis")
                .register(meterRegistry);

        this.redisFailureCounter = Counter.builder("feature_flag.cache.redis.failures")
                .description("Redis pub/sub subscription failures")
                .register(meterRegistry);

        meterRegistry.gauge("feature_flag.cache.staleness_window_seconds", maxStalenessMs,
                (v) -> v.get() / 1000d);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void subscribeToRedisUpdates() {
        try {
            if (circuitBreakerOpen.get()) {
                log.warn("Circuit breaker open, not subscribing to Redis cache updates");
                return;
            }

            listenerContainer.addMessageListener(this, new PatternTopic("flag-updates:*"));
            log.info("Subscribed to Redis pub/sub for flag cache invalidation");
        } catch (Exception e) {
            log.error("Failed to subscribe to Redis cache updates", e);
            redisFailureCounter.increment();
            triggerCircuitBreaker();
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        if (circuitBreakerOpen.get()) {
            return;
        }

        try {
            long start = System.currentTimeMillis();
            String channel = new String(message.getChannel());
            String payload = new String(message.getBody());

            handleCacheInvalidation(channel, payload);

            long duration = System.currentTimeMillis() - start;
            updateStalenessMetric(duration);
        } catch (Exception e) {
            log.warn("Error processing cache invalidation message", e);
            redisFailureCounter.increment();
        }
    }

    private void handleCacheInvalidation(String channel, String payload) {
        try {
            var json = objectMapper.readTree(payload);

            if (json.has("bulkUpdate") && json.get("bulkUpdate").asBoolean()) {
                invalidateBulkCaches(json.get("reason").asText());
            } else {
                String flagKey = json.get("flagKey").asText();
                invalidateFlagCache(flagKey);
            }

            invalidationCounter.increment();
            log.debug("Invalidated cache from channel={}", channel);
        } catch (Exception e) {
            log.warn("Failed to deserialize cache invalidation message: {}", payload, e);
            redisFailureCounter.increment();
        }
    }

    private void invalidateFlagCache(String flagKey) {
        try {
            var flagsCache = cacheManager.getCache("flags");
            if (flagsCache != null) {
                flagsCache.evict(flagKey);
            }

            var overridesCache = cacheManager.getCache("flag-overrides");
            if (overridesCache != null) {
                overridesCache.clear();
            }

            log.trace("Invalidated flag cache: key={}", flagKey);
        } catch (Exception e) {
            log.warn("Error invalidating flag cache for key={}", flagKey, e);
        }
    }

    private void invalidateBulkCaches(String reason) {
        try {
            var flagsCache = cacheManager.getCache("flags");
            if (flagsCache != null) {
                flagsCache.clear();
            }

            var overridesCache = cacheManager.getCache("flag-overrides");
            if (overridesCache != null) {
                overridesCache.clear();
            }

            var bulkCache = cacheManager.getCache("flag-overrides-bulk");
            if (bulkCache != null) {
                bulkCache.clear();
            }

            log.debug("Invalidated bulk flag caches: reason={}", reason);
        } catch (Exception e) {
            log.warn("Error invalidating bulk flag caches", e);
        }
    }

    private void updateStalenessMetric(long durationMs) {
        long current = maxStalenessMs.get();
        while (durationMs > current && !maxStalenessMs.compareAndSet(current, durationMs)) {
            current = maxStalenessMs.get();
        }
    }

    private void triggerCircuitBreaker() {
        if (circuitBreakerOpen.compareAndSet(false, true)) {
            log.error("Circuit breaker opened: Redis cache invalidation disabled. Will retry on next restart.");
            redisFailureCounter.increment();
        }
    }

    public boolean isCircuitBreakerOpen() {
        return circuitBreakerOpen.get();
    }
}
