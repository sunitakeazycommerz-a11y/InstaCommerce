package com.instacommerce.fraud.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager cacheManager = new SimpleCacheManager();
        cacheManager.setCaches(List.of(
                new CaffeineCache("fraudRules",
                        Caffeine.newBuilder()
                                .maximumSize(1000)
                                .expireAfterWrite(300, TimeUnit.SECONDS)
                                .build()),
                new CaffeineCache("blocklist",
                        Caffeine.newBuilder()
                                .maximumSize(5000)
                                .expireAfterWrite(60, TimeUnit.SECONDS)
                                .build())
        ));
        return cacheManager;
    }
}
