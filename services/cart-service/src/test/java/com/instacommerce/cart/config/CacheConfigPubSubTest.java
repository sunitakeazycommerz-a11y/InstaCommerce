package com.instacommerce.cart.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.instacommerce.cart.dto.response.CartItemResponse;
import com.instacommerce.cart.dto.response.CartResponse;
import com.instacommerce.platform.cache.CacheJsonSerializer;
import com.instacommerce.platform.cache.TwoLevelCacheManager;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("Cart CacheConfig Pub/Sub and Serialization Tests")
class CacheConfigPubSubTest {

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T instance) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(instance);
        return provider;
    }

    @Test
    @DisplayName("TwoLevelCache skips self-echo, only invalidates on evict/clear, and registers container")
    void cacheInvalidationPubSubAndContainerRegistration() {
        StringRedisTemplate templateA = mock(StringRedisTemplate.class);
        StringRedisTemplate templateB = mock(StringRedisTemplate.class);
        TwoLevelCacheManager managerA = new TwoLevelCacheManager(
                null,
                templateA,
                CacheConfig.INVALIDATION_CHANNEL,
                n -> Caffeine.newBuilder().maximumSize(50_000).expireAfterWrite(30, TimeUnit.SECONDS));
        TwoLevelCacheManager managerB = new TwoLevelCacheManager(
                null,
                templateB,
                CacheConfig.INVALIDATION_CHANNEL,
                n -> Caffeine.newBuilder().maximumSize(50_000).expireAfterWrite(30, TimeUnit.SECONDS));

        CacheConfig config = new CacheConfig();
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisMessageListenerContainer container = config.cacheInvalidationListenerContainer(factory, managerB);
        assertThat(container).isNotNull();
        assertThat(container.isAutoStartup()).isFalse();

        Cache cacheA = managerA.getCache("carts");
        Cache cacheB = managerB.getCache("carts");
        UUID userId = UUID.randomUUID();
        CartResponse cachedCart = new CartResponse(
                UUID.randomUUID(), userId, List.of(), 500L, 1, Instant.parse("2026-02-01T00:00:00Z"), 1L);
        cacheB.put(userId, cachedCart);

        cacheA.put(userId, cachedCart);
        verify(templateA, never()).convertAndSend(any(), any());

        cacheA.evict(userId);
        verify(templateA).convertAndSend(eq(CacheConfig.INVALIDATION_CHANNEL),
                argThat((String msg) -> msg.indexOf('|') > 0 && msg.endsWith("|carts:" + userId)));

        String remotePayload = "other-node|carts:" + userId;
        managerB.onMessage(new DefaultMessage(
                CacheConfig.INVALIDATION_CHANNEL.getBytes(StandardCharsets.UTF_8),
                remotePayload.getBytes(StandardCharsets.UTF_8)), null);
        assertThat(cacheB.get(userId)).isNull();
    }

    @Test
    @DisplayName("Redis serializer handles CartResponse Instant fields cleanly via JavaTimeModule")
    void redisSerializerRoundTripsCartResponse() {
        RedisSerializer<Object> serializer = CacheJsonSerializer.create();
        UUID cartId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        CartResponse cart = new CartResponse(
                cartId,
                userId,
                List.of(new CartItemResponse(productId, "Bread", 4500L, 1, 4500L)),
                4500L,
                1,
                Instant.parse("2026-02-01T12:30:00Z"),
                2L);

        byte[] bytes = serializer.serialize(cart);
        Object restored = serializer.deserialize(bytes);
        assertThat(restored).isEqualTo(cart);
    }

    @Test
    @DisplayName("cacheManager works without Redis beans")
    void cacheManagerWithoutRedisWorksGracefully() {
        CacheConfig config = new CacheConfig();
        ReflectionTestUtils.setField(config, "redisEnabled", true);
        CacheManager manager = config.cacheManager(providerOf(null), providerOf(null));
        assertThat(manager).isInstanceOf(MessageListener.class);
        assertThat(manager.getCache("carts")).isNotNull();
    }
}
