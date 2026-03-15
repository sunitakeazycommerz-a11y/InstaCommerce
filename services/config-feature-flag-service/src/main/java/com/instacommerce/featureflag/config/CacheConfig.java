package com.instacommerce.featureflag.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfig {

    @Value("${feature-flag.cache.flags-ttl-seconds:30}")
    private long flagsTtlSeconds;

    @Value("${feature-flag.cache.max-size:5000}")
    private long maxSize;

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
}
