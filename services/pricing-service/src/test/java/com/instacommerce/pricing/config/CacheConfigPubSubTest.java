package com.instacommerce.pricing.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.pricing.config.CacheConfig.TwoLevelCacheManager;
import com.instacommerce.pricing.dto.response.PriceCalculationResponse;
import com.instacommerce.pricing.dto.response.PricedItem;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
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
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("Pricing CacheConfig Pub/Sub and Serialization Tests")
class CacheConfigPubSubTest {

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T instance) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(instance);
        return provider;
    }

    @Test
    @DisplayName("TwoLevelCache skips self-echo, evicts only on evict/clear, and registers listener container")
    void cacheInvalidationPubSubAndContainerRegistration() {
        StringRedisTemplate templateA = mock(StringRedisTemplate.class);
        StringRedisTemplate templateB = mock(StringRedisTemplate.class);
        TwoLevelCacheManager managerA = new TwoLevelCacheManager(null, templateA);
        TwoLevelCacheManager managerB = new TwoLevelCacheManager(null, templateB);

        CacheConfig config = new CacheConfig();
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisMessageListenerContainer container = config.cacheInvalidationListenerContainer(factory, managerB);
        assertThat(container).isNotNull();
        assertThat(container.isAutoStartup()).isFalse();

        Cache cacheA = managerA.getCache("productPrices");
        Cache cacheB = managerB.getCache("productPrices");
        UUID sku = UUID.randomUUID();
        cacheB.put(sku, 500L);

        cacheA.put(sku, 600L);
        verify(templateA, never()).convertAndSend(any(), any());
        assertThat(cacheB.get(sku, Long.class)).isEqualTo(500L);

        cacheA.evict(sku);
        verify(templateA).convertAndSend(eq(CacheConfig.INVALIDATION_CHANNEL),
                argThat((String msg) -> msg.indexOf('|') > 0 && msg.endsWith("|productPrices:" + sku)));

        String sentPayload = "other-node|productPrices:" + sku;
        managerB.onMessage(new DefaultMessage(
                CacheConfig.INVALIDATION_CHANNEL.getBytes(StandardCharsets.UTF_8),
                sentPayload.getBytes(StandardCharsets.UTF_8)), null);
        assertThat(cacheB.get(sku)).isNull();
    }

    @Test
    @DisplayName("Redis serializer handles PriceCalculationResponse with JavaTimeModule")
    @SuppressWarnings({"deprecation", "removal"})
    void redisSerializerRoundTripsPriceCalculationResponse() {
        var serializer = CacheConfig.jsonRedisSerializer();
        UUID productId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        PriceCalculationResponse response = new PriceCalculationResponse(
                List.of(new PricedItem(productId, 1000L, 2, 2000L, "INR")),
                2000L,
                200L,
                0L,
                1800L,
                "INR",
                List.of("PROMO10"),
                "SAVE10",
                quoteId,
                "hmac-token",
                90L,
                new BigDecimal("0.05"));

        byte[] bytes = serializer.serialize(response);
        Object restored = serializer.deserialize(bytes);
        assertThat(restored).isEqualTo(response);
    }

    @Test
    @DisplayName("cacheManager works without Redis beans")
    void cacheManagerWithoutRedisWorksGracefully() {
        CacheConfig config = new CacheConfig();
        ReflectionTestUtils.setField(config, "redisEnabled", true);
        CacheManager manager = config.cacheManager(providerOf(null), providerOf(null));
        assertThat(manager).isInstanceOf(MessageListener.class);
        Cache cache = manager.getCache("productPrices");
        cache.put("k", 123L);
        assertThat(cache.get("k", Long.class)).isEqualTo(123L);
    }
}
