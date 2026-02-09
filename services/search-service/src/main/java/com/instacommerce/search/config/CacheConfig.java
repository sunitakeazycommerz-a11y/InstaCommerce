package com.instacommerce.search.config;

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
                new CaffeineCache("searchResults",
                        Caffeine.newBuilder()
                                .maximumSize(10000)
                                .expireAfterWrite(5, TimeUnit.MINUTES)
                                .build()),
                new CaffeineCache("autocomplete",
                        Caffeine.newBuilder()
                                .maximumSize(5000)
                                .expireAfterWrite(2, TimeUnit.MINUTES)
                                .build()),
                new CaffeineCache("trending",
                        Caffeine.newBuilder()
                                .maximumSize(100)
                                .expireAfterWrite(1, TimeUnit.MINUTES)
                                .build())
        ));
        return cacheManager;
    }
}
