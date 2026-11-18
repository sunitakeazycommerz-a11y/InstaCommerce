package com.instacommerce.pricing.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.instacommerce.platform.cache.CacheJsonSerializer;
import com.instacommerce.platform.cache.TwoLevelCacheManager;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
@EnableCaching
public class CacheConfig {

    public static final String INVALIDATION_CHANNEL = "pricing:cache:invalidate";
    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Value("${pricing.cache.redis.enabled:true}")
    private boolean redisEnabled;

    @Bean
    @Primary
    public CacheManager cacheManager(
            ObjectProvider<RedisConnectionFactory> connectionFactoryProvider,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider) {

        RedisConnectionFactory connectionFactory = connectionFactoryProvider.getIfAvailable();
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();

        RedisCacheManager redisCacheManager = null;
        if (redisEnabled && connectionFactory != null) {
            try {
                RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                        .prefixCacheNameWith("pricing:")
                        .entryTtl(Duration.ofMinutes(5))
                        .serializeKeysWith(
                                RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                        .serializeValuesWith(
                                RedisSerializationContext.SerializationPair.fromSerializer(CacheJsonSerializer.create()));

                Map<String, RedisCacheConfiguration> initialConfigs = new HashMap<>();
                initialConfigs.put("productPrices", defaultConfig.entryTtl(Duration.ofMinutes(5)));
                initialConfigs.put("activePromotions", defaultConfig.entryTtl(Duration.ofMinutes(2)));
                initialConfigs.put("priceRules", defaultConfig.entryTtl(Duration.ofMinutes(5)));

                redisCacheManager = RedisCacheManager.builder(connectionFactory)
                        .cacheDefaults(defaultConfig)
                        .withInitialCacheConfigurations(initialConfigs)
                        .build();
                log.info("Initialized distributed RedisCacheManager for pricing-service");
            } catch (Exception e) {
                log.warn("Failed to initialize RedisCacheManager in pricing-service, falling back to L1 Caffeine near-cache: {}",
                        e.getMessage());
            }
        }

        return new TwoLevelCacheManager(
                redisCacheManager,
                redisTemplate,
                INVALIDATION_CHANNEL,
                name -> {
                    long l1TtlSeconds = "activePromotions".equals(name) ? 30 : 15;
                    return Caffeine.newBuilder()
                            .maximumSize(10_000)
                            .expireAfterWrite(l1TtlSeconds, TimeUnit.SECONDS);
                });
    }

    @Bean
    @ConditionalOnBean(RedisConnectionFactory.class)
    @ConditionalOnProperty(name = "pricing.cache.redis.enabled", havingValue = "true", matchIfMissing = true)
    public RedisMessageListenerContainer cacheInvalidationListenerContainer(
            RedisConnectionFactory connectionFactory,
            CacheManager cacheManager) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setAutoStartup(false);
        if (cacheManager instanceof MessageListener listener) {
            container.addMessageListener(listener, ChannelTopic.of(INVALIDATION_CHANNEL));
        }
        return container;
    }

    @Bean
    @ConditionalOnBean(RedisMessageListenerContainer.class)
    public ApplicationListener<ApplicationReadyEvent> cacheInvalidationListenerStarter(
            RedisMessageListenerContainer container,
            RedisConnectionFactory connectionFactory) {
        return event -> {
            try (RedisConnection connection = connectionFactory.getConnection()) {
                connection.ping();
                container.start();
                log.info("Subscribed to Redis cache invalidation channel {}", INVALIDATION_CHANNEL);
            } catch (Exception e) {
                log.warn("Redis unavailable at startup; skipping Pub/Sub listener start in pricing-service: {}",
                        e.getMessage());
            }
        };
    }
}
