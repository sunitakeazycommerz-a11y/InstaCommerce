package com.instacommerce.identity.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import java.time.Duration;
import java.util.Collections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

@Service
public class RateLimitService {
    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    private static final RedisScript<Long> RATE_LIMIT_SCRIPT = new DefaultRedisScript<>(
        "local current = redis.call('INCR', KEYS[1])\n" +
        "if current == 1 then\n" +
        "    redis.call('EXPIRE', KEYS[1], ARGV[1])\n" +
        "end\n" +
        "return current\n",
        Long.class
    );

    private final RateLimiterRegistry rateLimiterRegistry;
    private final Cache<String, RateLimiter> limiters;
    private final StringRedisTemplate redisTemplate;

    public RateLimitService(RateLimiterRegistry rateLimiterRegistry, ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.rateLimiterRegistry = rateLimiterRegistry;
        this.redisTemplate = redisTemplateProvider != null ? redisTemplateProvider.getIfAvailable() : null;
        this.limiters = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(Duration.ofMinutes(5))
            .build();
    }

    public RateLimitService(RateLimiterRegistry rateLimiterRegistry, StringRedisTemplate redisTemplate) {
        this.rateLimiterRegistry = rateLimiterRegistry;
        this.redisTemplate = redisTemplate;
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
        RateLimiterConfig config = resolveConfig(baseName);
        if (redisTemplate != null) {
            try {
                String redisKey = "identity:ratelimit:" + baseName + ":" + (ipAddress == null ? "unknown" : ipAddress);
                long windowSeconds = Math.max(1, config.getLimitRefreshPeriod().getSeconds());
                Long currentCount = redisTemplate.execute(
                    RATE_LIMIT_SCRIPT,
                    Collections.singletonList(redisKey),
                    String.valueOf(windowSeconds)
                );
                if (currentCount != null && currentCount > config.getLimitForPeriod()) {
                    RateLimiter limiter = getLocalLimiter(baseName, ipAddress);
                    throw RequestNotPermitted.createRequestNotPermitted(limiter);
                }
                return;
            } catch (RequestNotPermitted e) {
                throw e;
            } catch (Exception e) {
                log.warn("Redis rate limiter unavailable for {}, falling back to local Caffeine limiter: {}", baseName, e.getMessage());
            }
        }

        checkLocal(baseName, ipAddress);
    }

    private void checkLocal(String baseName, String ipAddress) {
        RateLimiter limiter = getLocalLimiter(baseName, ipAddress);
        if (!limiter.acquirePermission()) {
            throw RequestNotPermitted.createRequestNotPermitted(limiter);
        }
    }

    private RateLimiter getLocalLimiter(String baseName, String ipAddress) {
        String key = baseName + ":" + (ipAddress == null ? "unknown" : ipAddress);
        return limiters.get(key, name -> rateLimiterRegistry.rateLimiter(name, resolveConfig(baseName)));
    }

    private RateLimiterConfig resolveConfig(String baseName) {
        return rateLimiterRegistry.rateLimiter(baseName).getRateLimiterConfig();
    }
}
