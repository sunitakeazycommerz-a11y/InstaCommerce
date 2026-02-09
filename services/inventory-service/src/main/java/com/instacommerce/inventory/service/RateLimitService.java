package com.instacommerce.inventory.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RateLimitService {

    private final Cache<String, RateLimiter> limiters;
    private final int limitForPeriod;
    private final Duration limitRefreshPeriod;

    public RateLimitService(
            @Value("${rate-limit.requests-per-period:50}") int limitForPeriod,
            @Value("${rate-limit.period-seconds:60}") int periodSeconds) {
        this.limitForPeriod = limitForPeriod;
        this.limitRefreshPeriod = Duration.ofSeconds(periodSeconds);
        this.limiters = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterAccess(Duration.ofMinutes(5))
                .build();
    }

    public boolean tryAcquire(String key) {
        RateLimiter limiter = limiters.get(key, k -> RateLimiter.of(k,
                RateLimiterConfig.custom()
                        .limitForPeriod(limitForPeriod)
                        .limitRefreshPeriod(limitRefreshPeriod)
                        .timeoutDuration(Duration.ZERO)
                        .build()));
        return limiter.acquirePermission();
    }
}
