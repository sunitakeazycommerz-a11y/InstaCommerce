package com.instacommerce.identity.service;

import com.instacommerce.identity.config.IdentityProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class TokenBlocklistService {
    private static final String JTI_PREFIX = "identity:revoked_jti:";
    private static final String USER_REVOKED_PREFIX = "identity:revoked_user_before:";
    private static final int LOCAL_PRUNE_THRESHOLD = 4096;

    private final StringRedisTemplate redisTemplate;
    private final long accessTtlSeconds;
    private final Map<String, Long> localRevokedJtis = new ConcurrentHashMap<>();
    private final Map<String, Long> localUserRevokedBefore = new ConcurrentHashMap<>();

    @Autowired
    public TokenBlocklistService(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                 IdentityProperties identityProperties) {
        this.redisTemplate = redisTemplateProvider != null ? redisTemplateProvider.getIfAvailable() : null;
        long configuredTtl = identityProperties != null ? identityProperties.getToken().getAccessTtlSeconds() : 900L;
        this.accessTtlSeconds = Math.max(60L, configuredTtl);
    }

    public TokenBlocklistService(StringRedisTemplate redisTemplate, long accessTtlSeconds) {
        this.redisTemplate = redisTemplate;
        this.accessTtlSeconds = Math.max(60L, accessTtlSeconds);
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
                redisTemplate.opsForValue().set(JTI_PREFIX + jti, "1", Duration.ofSeconds(effectiveTtl));
            } catch (Exception ignored) {
                // Local blocklist remains active as degraded fallback
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
                redisTemplate.opsForValue().set(
                        USER_REVOKED_PREFIX + userId,
                        Long.toString(nowMillis),
                        Duration.ofSeconds(accessTtlSeconds));
            } catch (Exception ignored) {
                // Local cutoff remains active as degraded fallback
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
                } catch (Exception ignored) {
                    // Fall through to local/user checks
                }
            }
        }

        if (subject != null && !subject.isBlank()) {
            Long revokedBeforeMillis = resolveUserRevokedBefore(subject);
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

    private Long resolveUserRevokedBefore(String subject) {
        Long local = localUserRevokedBefore.get(subject);
        if (redisTemplate != null) {
            try {
                String value = redisTemplate.opsForValue().get(USER_REVOKED_PREFIX + subject);
                if (value != null) {
                    long parsed = Long.parseLong(value);
                    return local != null ? Math.max(local, parsed) : parsed;
                }
            } catch (Exception ignored) {
                // Fall back to local cutoff
            }
        }
        return local;
    }

    private void pruneIfNeeded(Map<String, Long> map, long nowMillis) {
        if (map.size() >= LOCAL_PRUNE_THRESHOLD) {
            map.entrySet().removeIf(entry -> entry.getValue() <= nowMillis);
        }
    }
}
