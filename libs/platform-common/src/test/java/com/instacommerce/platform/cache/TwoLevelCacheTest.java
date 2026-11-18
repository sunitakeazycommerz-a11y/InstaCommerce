package com.instacommerce.platform.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;

@DisplayName("TwoLevelCache and CacheJsonSerializer tests")
class TwoLevelCacheTest {

    private static final String CHANNEL = "test:cache:invalidate";

    @Test
    @DisplayName("put skips Pub/Sub publish while evict and clear invalidate peers without self-echo")
    void pubSubInvalidatesPeerWithoutSelfEcho() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        TwoLevelCacheManager managerA = new TwoLevelCacheManager(
                null, template, CHANNEL, name -> Caffeine.newBuilder().maximumSize(100).expireAfterWrite(10, TimeUnit.SECONDS));
        TwoLevelCacheManager managerB = new TwoLevelCacheManager(
                null, template, CHANNEL, name -> Caffeine.newBuilder().maximumSize(100).expireAfterWrite(10, TimeUnit.SECONDS));

        Cache cacheA = managerA.getCache("items");
        Cache cacheB = managerB.getCache("items");
        UUID id = UUID.randomUUID();

        cacheA.put(id, "value-a");
        cacheB.put(id, "value-b");
        verify(template, never()).convertAndSend(any(), any());

        cacheA.evict(id);
        verify(template).convertAndSend(eq(CHANNEL),
                argThat((String msg) -> msg.indexOf('|') > 0 && msg.endsWith("|items:" + id)));

        String peerPayload = "peer-instance|items:" + id;
        managerB.onMessage(new DefaultMessage(
                CHANNEL.getBytes(StandardCharsets.UTF_8),
                peerPayload.getBytes(StandardCharsets.UTF_8)), null);
        assertThat(cacheB.get(id)).isNull();

        cacheB.put(10, "num-val");
        managerB.onMessage(new DefaultMessage(
                CHANNEL.getBytes(StandardCharsets.UTF_8),
                "peer-instance|items:10".getBytes(StandardCharsets.UTF_8)), null);
        assertThat(cacheB.get(10)).isNull();
    }

    @Test
    @DisplayName("CacheJsonSerializer preserves Instant timestamps via JavaTimeModule")
    void serializerPreservesInstant() {
        RedisSerializer<Object> serializer = CacheJsonSerializer.create();
        Instant now = Instant.parse("2026-03-01T10:15:30Z");
        byte[] serialized = serializer.serialize(Map.of("createdAt", now));
        assertThat(serialized).isNotNull();
        assertThat(new String(serialized, StandardCharsets.UTF_8)).contains("2026-03-01T10:15:30Z");
    }
}
