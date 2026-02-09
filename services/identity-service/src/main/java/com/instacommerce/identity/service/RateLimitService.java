package com.instacommerce.identity.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import java.time.Duration;
import org.springframework.stereotype.Service;

@Service
public class RateLimitService {
    private final RateLimiterRegistry rateLimiterRegistry;
    private final Cache<String, RateLimiter> limiters;

    public RateLimitService(RateLimiterRegistry rateLimiterRegistry) {
        this.rateLimiterRegistry = rateLimiterRegistry;
        this.limiters = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(Duration.ofMinutes(5))
            .build();
    }

    public void checkLogin(String ipAddress) {
        check("loginLimiter", ipAddress);
    }

    public void checkRegister(String ipAddress) {
        check("registerLimiter", ipAddress);
    }

    private void check(String baseName, String ipAddress) {
        String key = baseName + ":" + (ipAddress == null ? "unknown" : ipAddress);
        RateLimiter limiter = limiters.get(key, name -> rateLimiterRegistry.rateLimiter(
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
