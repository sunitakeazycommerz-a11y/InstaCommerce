package com.instacommerce.order.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RateLimitService {
    private final RateLimiterRegistry rateLimiterRegistry;
    private final Cache<String, RateLimiter> limiters = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterAccess(Duration.ofMinutes(5))
        .build();

    public RateLimitService(RateLimiterRegistry rateLimiterRegistry) {
        this.rateLimiterRegistry = rateLimiterRegistry;
    }

    public void checkCheckout(UUID userId) {
        String key = userId == null ? "anonymous" : userId.toString();
        check("checkoutLimiter", key);
    }

    private void check(String baseName, String key) {
        String limiterKey = baseName + ":" + (key == null ? "unknown" : key);
        RateLimiter limiter = limiters.get(limiterKey, name -> rateLimiterRegistry.rateLimiter(
            name,
            resolveConfig(baseName)));
        if (!limiter.acquirePermission()) {
            throw RequestNotPermitted.createRequestNotPermitted(limiter);
        }
    }

    private RateLimiterConfig resolveConfig(String baseName) {
        return rateLimiterRegistry.rateLimiter(baseName).getRateLimiterConfig();
    }
}
