package com.instacommerce.identity.service;

import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimitServiceTest {

    private RateLimiterRegistry registry;
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        RateLimiterConfig loginConfig = RateLimiterConfig.custom()
            .limitForPeriod(5)
            .limitRefreshPeriod(Duration.ofSeconds(60))
            .timeoutDuration(Duration.ZERO)
            .build();
        RateLimiterConfig registerConfig = RateLimiterConfig.custom()
            .limitForPeriod(3)
            .limitRefreshPeriod(Duration.ofSeconds(60))
            .timeoutDuration(Duration.ZERO)
            .build();

        registry = RateLimiterRegistry.of(loginConfig);
        registry.rateLimiter("loginLimiter", loginConfig);
        registry.rateLimiter("registerLimiter", registerConfig);

        redisTemplate = mock(StringRedisTemplate.class);
    }

    @Test
    @DisplayName("Allows requests when Redis count is under the limit")
    void shouldAllowRequestsUnderLimitWithRedis() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
            .thenReturn(1L, 2L, 3L, 4L, 5L);

        RateLimitService service = new RateLimitService(registry, redisTemplate);

        for (int i = 0; i < 5; i++) {
            assertThatCode(() -> service.checkLogin("192.168.1.100")).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("Blocks requests and throws RequestNotPermitted across 5 simulated nodes when Redis counter exceeds limit")
    void shouldBlockRequestsAcrossSimulatedNodesWhenLimitExceeded() {
        AtomicLong sharedRedisCounter = new AtomicLong(0);

        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
            .thenAnswer(invocation -> sharedRedisCounter.incrementAndGet());

        // 5 simulated node instances sharing the same distributed Redis template
        RateLimitService[] clusterNodes = new RateLimitService[5];
        for (int i = 0; i < 5; i++) {
            clusterNodes[i] = new RateLimitService(registry, redisTemplate);
        }

        // Each of the 5 nodes processes 1 request (total 5 requests) -> all succeed
        for (int i = 0; i < 5; i++) {
            final int nodeIndex = i;
            assertThatCode(() -> clusterNodes[nodeIndex].checkLogin("203.0.113.42")).doesNotThrowAnyException();
        }

        // 6th request from any node must be rejected because shared Redis counter is 6 > 5
        assertThatThrownBy(() -> clusterNodes[0].checkLogin("203.0.113.42"))
            .isInstanceOf(RequestNotPermitted.class);
    }

    @Test
    @DisplayName("Gracefully falls back to local Caffeine limiter when Redis throws connection error")
    void shouldFallbackToLocalLimiterWhenRedisFails() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
            .thenThrow(new RedisConnectionFailureException("Redis server unreachable"));

        RateLimitService service = new RateLimitService(registry, redisTemplate);

        // First 5 requests succeed via local fallback
        for (int i = 0; i < 5; i++) {
            assertThatCode(() -> service.checkLogin("10.0.0.1")).doesNotThrowAnyException();
        }

        // 6th request fails via local limiter
        assertThatThrownBy(() -> service.checkLogin("10.0.0.1"))
            .isInstanceOf(RequestNotPermitted.class);
    }

    @Test
    @DisplayName("Uses local Caffeine limiter when Redis template is null")
    void shouldWorkLocallyWhenRedisTemplateNull() {
        RateLimitService service = new RateLimitService(registry, (StringRedisTemplate) null);

        for (int i = 0; i < 3; i++) {
            assertThatCode(() -> service.checkRegister("10.0.0.2")).doesNotThrowAnyException();
        }

        assertThatThrownBy(() -> service.checkRegister("10.0.0.2"))
            .isInstanceOf(RequestNotPermitted.class);
    }
}
