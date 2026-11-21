package com.instacommerce.identity.service;

import com.instacommerce.identity.config.IdentityProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

@Service
public class TokenBlocklistService {
    private static final Logger log = LoggerFactory.getLogger(TokenBlocklistService.class);
    private static final String JTI_PREFIX = "identity:revoked_jti:";
    private static final String USER_REVOKED_PREFIX = "identity:revoked_user_before:";
    private static final int LOCAL_PRUNE_THRESHOLD = 4096;

    private final StringRedisTemplate redisTemplate;
    private final long accessTtlSeconds;
    private final Counter writeFailureCounter;
    private final Counter lookupFailureCounter;
    private final Map<String, Long> localRevokedJtis = new ConcurrentHashMap<>();
    private final Map<String, Long> localUserRevokedBefore = new ConcurrentHashMap<>();

    @Autowired
    public TokenBlocklistService(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                 IdentityProperties identityProperties,
                                 ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.redisTemplate = redisTemplateProvider != null ? redisTemplateProvider.getIfAvailable() : null;
        long configuredTtl = identityProperties != null ? identityProperties.getToken().getAccessTtlSeconds() : 900L;
        this.accessTtlSeconds = Math.max(60L, configuredTtl);
        MeterRegistry registry = meterRegistryProvider != null ? meterRegistryProvider.getIfAvailable() : null;
        if (registry == null) {
            registry = new SimpleMeterRegistry();
        }
        this.writeFailureCounter = Counter.builder("auth.token.blocklist.redis.failures")
                .tag("operation", "write")
                .register(registry);
        this.lookupFailureCounter = Counter.builder("auth.token.blocklist.redis.failures")
                .tag("operation", "lookup")
                .register(registry);
    }

    public void revokeAccessToken(String jti, long ttlSeconds) {
        if (jti == null || jti.isBlank()) {
            return;
        }
        long effectiveTtl = Math.max(1L, ttlSeconds > 0 ? ttlSeconds : accessTtlSeconds);
        long expiresAtMillis = System.currentTimeMillis() + effectiveTtl * 1000L;
        pruneIfNeeded(localRevokedJtis, System.currentTimeMillis());
        localRevokedJtis.put(jti, expiresAtMillis);

        if (redisTemplate != null) {
            try {
                ValueOperations<String, String> ops = redisTemplate.opsForValue();
                if (ops == null) {
                    throw new IllegalStateException("Redis ValueOperations unavailable for token blocklist write");
                }
                ops.set(JTI_PREFIX + jti, "1", Duration.ofSeconds(effectiveTtl));
            } catch (Exception ex) {
                writeFailureCounter.increment();
                log.error("Failed to persist JTI revocation to Redis jti={}", jti, ex);
                throw new IllegalStateException("Failed to persist access token revocation", ex);
            }
        }
    }

    public void revokeAllUserTokens(UUID userId) {
        if (userId == null) {
            return;
        }
        long nowMillis = System.currentTimeMillis();
        if (localUserRevokedBefore.size() >= LOCAL_PRUNE_THRESHOLD) {
            long cutoff = nowMillis - accessTtlSeconds * 1000L;
            localUserRevokedBefore.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        }
        localUserRevokedBefore.put(userId.toString(), nowMillis);

        if (redisTemplate != null) {
            try {
                ValueOperations<String, String> ops = redisTemplate.opsForValue();
                if (ops == null) {
                    throw new IllegalStateException("Redis ValueOperations unavailable for user revocation write");
                }
                ops.set(
                        USER_REVOKED_PREFIX + userId,
                        Long.toString(nowMillis),
                        Duration.ofSeconds(accessTtlSeconds));
            } catch (Exception ex) {
                writeFailureCounter.increment();
                log.error("Failed to persist user token revocation cutoff to Redis userId={}", userId, ex);
                throw new IllegalStateException("Failed to persist user token revocation", ex);
            }
        }
    }

    public boolean isTokenRevoked(String jti, String subject, Date issuedAt, Long issuedAtMillis) {
        long nowMillis = System.currentTimeMillis();
        if (jti != null && !jti.isBlank()) {
            Long localExpiry = localRevokedJtis.get(jti);
            if (localExpiry != null) {
                if (localExpiry > nowMillis) {
                    return true;
                }
                localRevokedJtis.remove(jti, localExpiry);
            }
            if (redisTemplate != null) {
                try {
                    if (Boolean.TRUE.equals(redisTemplate.hasKey(JTI_PREFIX + jti))) {
                        return true;
                    }
                } catch (Exception ex) {
                    lookupFailureCounter.increment();
                    log.error("Redis lookup failed checking JTI revocation jti={}; failing closed", jti, ex);
                    return true;
                }
            }
        }

        if (subject != null && !subject.isBlank()) {
            UserRevocationLookup lookup = resolveUserRevokedBefore(subject);
            if (lookup.unavailable()) {
                return true;
            }
            Long revokedBeforeMillis = lookup.revokedBeforeMillis();
            if (revokedBeforeMillis != null) {
                if (issuedAtMillis != null) {
                    return issuedAtMillis <= revokedBeforeMillis;
                }
                if (issuedAt != null) {
                    long issuedSecond = Instant.ofEpochMilli(issuedAt.getTime()).getEpochSecond();
                    long revokedSecond = Instant.ofEpochMilli(revokedBeforeMillis).getEpochSecond();
                    return issuedSecond <= revokedSecond;
                }
                return true;
            }
        }
        return false;
    }

    private record UserRevocationLookup(boolean unavailable, Long revokedBeforeMillis) {
    }

    private UserRevocationLookup resolveUserRevokedBefore(String subject) {
        Long local = localUserRevokedBefore.get(subject);
        if (redisTemplate != null) {
            try {
                ValueOperations<String, String> ops = redisTemplate.opsForValue();
                if (ops == null) {
                    lookupFailureCounter.increment();
                    log.error("Redis ValueOperations unavailable resolving revocation cutoff userId={}; failing closed",
                            subject);
                    return new UserRevocationLookup(true, local);
                }
                String value = ops.get(USER_REVOKED_PREFIX + subject);
                if (value != null) {
                    long parsed = Long.parseLong(value);
                    Long resolved = local != null ? Math.max(local, parsed) : parsed;
                    return new UserRevocationLookup(false, resolved);
                }
            } catch (Exception ex) {
                lookupFailureCounter.increment();
                log.error("Redis lookup failed resolving user revocation cutoff userId={}; failing closed",
                        subject, ex);
                return new UserRevocationLookup(true, local);
            }
        }
        return new UserRevocationLookup(false, local);
    }

    private void pruneIfNeeded(Map<String, Long> map, long nowMillis) {
        if (map.size() >= LOCAL_PRUNE_THRESHOLD) {
            map.entrySet().removeIf(entry -> entry.getValue() <= nowMillis);
        }
    }
}
