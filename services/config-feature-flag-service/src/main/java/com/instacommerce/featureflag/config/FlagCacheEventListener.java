package com.instacommerce.featureflag.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "feature-flag.cache.redis-enabled", havingValue = "true", matchIfMissing = true)
public class FlagCacheEventListener {

    private static final Logger log = LoggerFactory.getLogger(FlagCacheEventListener.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public FlagCacheEventListener(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
    }

    public void publishFlagUpdate(UUID flagId, String flagKey, String value) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("flagId", flagId.toString());
            payload.put("flagKey", flagKey);
            payload.put("value", value);
            payload.put("timestamp", Instant.now().toEpochMilli());

            String message = objectMapper.writeValueAsString(payload);
            String overrideKey = "flag-updates:" + flagKey;

            redisTemplate.convertAndSend(overrideKey, message);
            redisTemplate.convertAndSend("flag-updates:all", message);

            log.debug("Published flag update: flagKey={}, channel={}", flagKey, overrideKey);
        } catch (Exception e) {
            log.warn("Failed to publish flag cache update for flagKey={}", flagKey, e);
        }
    }

    public void publishBulkUpdate(String updateReason) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("bulkUpdate", true);
            payload.put("reason", updateReason);
            payload.put("timestamp", Instant.now().toEpochMilli());

            String message = objectMapper.writeValueAsString(payload);
            redisTemplate.convertAndSend("flag-updates:all", message);

            log.debug("Published bulk flag update: reason={}", updateReason);
        } catch (Exception e) {
            log.warn("Failed to publish bulk flag cache update", e);
        }
    }
}
