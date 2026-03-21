package com.instacommerce.featureflag.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
@EnableCaching
public class CacheConfig {

    @Value("${feature-flag.cache.flags-ttl-seconds:30}")
    private long flagsTtlSeconds;

    @Value("${feature-flag.cache.max-size:5000}")
    private long maxSize;

    @Value("${feature-flag.cache.redis-enabled:true}")
    private boolean redisEnabled;

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager("flags", "flag-overrides", "flag-overrides-bulk");
        // Default spec for flags and flag-overrides
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(flagsTtlSeconds, TimeUnit.SECONDS));
        // Register a shorter TTL for the bulk overrides cache
        manager.registerCustomCache("flag-overrides-bulk",
                Caffeine.newBuilder()
                        .maximumSize(maxSize)
                        .expireAfterWrite(10, TimeUnit.SECONDS)
                        .build());
        return manager;
    }

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        return new LettuceConnectionFactory();
    }

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        return template;
    }

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            MeterRegistry meterRegistry) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }
}
