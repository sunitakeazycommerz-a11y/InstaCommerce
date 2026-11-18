package com.instacommerce.catalog.config;

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
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
@EnableCaching
public class CacheConfig {

    public static final String INVALIDATION_CHANNEL = "catalog:cache:invalidate";
    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Value("${catalog.cache.redis.enabled:true}")
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
                        .prefixCacheNameWith("catalog:")
                        .entryTtl(Duration.ofMinutes(5))
                        .serializeKeysWith(
                                RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                        .serializeValuesWith(
                                RedisSerializationContext.SerializationPair.fromSerializer(CacheJsonSerializer.create()));

                Map<String, RedisCacheConfiguration> initialConfigs = new HashMap<>();
                initialConfigs.put("products", defaultConfig.entryTtl(Duration.ofMinutes(5)));
                initialConfigs.put("categories", defaultConfig.entryTtl(Duration.ofMinutes(10)));
                initialConfigs.put("search", defaultConfig.entryTtl(Duration.ofMinutes(1)));

                redisCacheManager = RedisCacheManager.builder(connectionFactory)
                        .cacheDefaults(defaultConfig)
                        .withInitialCacheConfigurations(initialConfigs)
                        .build();
                log.info("Initialized distributed RedisCacheManager for catalog-service");
            } catch (Exception e) {
                log.warn("Failed to initialize RedisCacheManager, falling back to L1 Caffeine near-cache: {}",
                        e.getMessage());
            }
        }

        return new TwoLevelCacheManager(
                redisCacheManager,
                redisTemplate,
                redisEnabled ? connectionFactory : null,
                INVALIDATION_CHANNEL,
                name -> {
                    long l1TtlSeconds = switch (name) {
                        case "categories" -> 30;
                        case "search" -> 10;
                        default -> 15;
                    };
                    return Caffeine.newBuilder()
                            .maximumSize(5_000)
                            .expireAfterWrite(l1TtlSeconds, TimeUnit.SECONDS);
                });
    }
}
