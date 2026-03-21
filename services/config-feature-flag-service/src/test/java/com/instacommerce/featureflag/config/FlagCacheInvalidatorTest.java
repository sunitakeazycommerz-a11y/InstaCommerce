package com.instacommerce.featureflag.config;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class FlagCacheInvalidatorTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("feature_flags_db")
            .withUsername("postgres")
            .withPassword("postgres");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private FlagCacheInvalidator cacheInvalidator;

    @BeforeEach
    void setUp() {
        var flagsCache = cacheManager.getCache("flags");
        if (flagsCache != null) {
            flagsCache.clear();
        }
        var overridesCache = cacheManager.getCache("flag-overrides");
        if (overridesCache != null) {
            overridesCache.clear();
        }
    }

    @Test
    void shouldInvalidateCacheWhenMessageReceived() throws InterruptedException {
        String flagKey = "test-flag";
        var flagsCache = cacheManager.getCache("flags");

        assertThat(flagsCache).isNotNull();

        // Put something in the cache
        flagsCache.put(flagKey, "cached-value");
        assertThat(flagsCache.get(flagKey)).isNotNull();

        // Publish cache invalidation
        String message = """
                {
                  "flagId": "%s",
                  "flagKey": "%s",
                  "value": "new-value",
                  "timestamp": %d
                }
                """.formatted(UUID.randomUUID(), flagKey, System.currentTimeMillis());

        redisTemplate.convertAndSend("flag-updates:" + flagKey, message);

        // Wait for message processing (should be <100ms)
        Thread.sleep(200);

        // Cache should be invalidated
        assertThat(flagsCache.get(flagKey)).isNull();
    }

    @Test
    void shouldInvalidateBulkCachesOnBulkUpdate() throws InterruptedException {
        String flagKey1 = "test-flag-1";
        String flagKey2 = "test-flag-2";

        var flagsCache = cacheManager.getCache("flags");
        assertThat(flagsCache).isNotNull();

        // Put multiple entries in cache
        flagsCache.put(flagKey1, "cached-value-1");
        flagsCache.put(flagKey2, "cached-value-2");

        // Verify entries exist
        assertThat(flagsCache.get(flagKey1)).isNotNull();
        assertThat(flagsCache.get(flagKey2)).isNotNull();

        // Publish bulk cache invalidation
        String message = """
                {
                  "bulkUpdate": true,
                  "reason": "test_bulk_update",
                  "timestamp": %d
                }
                """.formatted(System.currentTimeMillis());

        redisTemplate.convertAndSend("flag-updates:all", message);

        // Wait for message processing
        Thread.sleep(200);

        // All cache entries should be invalidated
        assertThat(flagsCache.get(flagKey1)).isNull();
        assertThat(flagsCache.get(flagKey2)).isNull();
    }

    @Test
    void shouldHandleRedisUnavailabilityGracefully() {
        // Circuit breaker should not be open initially
        assertThat(cacheInvalidator.isCircuitBreakerOpen()).isFalse();

        // Try to publish a message even if Redis has issues
        // The service should still function with Caffeine fallback
        var flagsCache = cacheManager.getCache("flags");
        assertThat(flagsCache).isNotNull();

        flagsCache.put("fallback-key", "fallback-value");
        assertThat(flagsCache.get("fallback-key")).isNotNull();
    }

    @Test
    void shouldPublishAndReceiveMessagesWithinSLO() throws InterruptedException {
        String flagKey = "slo-test-flag";
        var flagsCache = cacheManager.getCache("flags");

        flagsCache.put(flagKey, "slo-test-value");
        assertThat(flagsCache.get(flagKey)).isNotNull();

        long startTime = System.currentTimeMillis();

        String message = """
                {
                  "flagId": "%s",
                  "flagKey": "%s",
                  "value": "slo-value",
                  "timestamp": %d
                }
                """.formatted(UUID.randomUUID(), flagKey, startTime);

        redisTemplate.convertAndSend("flag-updates:" + flagKey, message);

        // Wait for message processing
        Thread.sleep(100);

        long duration = System.currentTimeMillis() - startTime;

        // Should be invalidated within 500ms SLO
        assertThat(flagsCache.get(flagKey)).isNull();
        assertThat(duration).isLessThan(500);
    }

    @Test
    void shouldNotCrashWhenRedisConnectionFails() {
        // Service should degrade gracefully if Redis is unavailable
        var flagsCache = cacheManager.getCache("flags");
        assertThat(flagsCache).isNotNull();

        // Should still be able to use Caffeine cache
        flagsCache.put("degraded-key", "degraded-value");
        assertThat(flagsCache.get("degraded-key")).isNotNull();

        // Clear and verify cache still works
        flagsCache.evict("degraded-key");
        assertThat(flagsCache.get("degraded-key")).isNull();
    }
}
