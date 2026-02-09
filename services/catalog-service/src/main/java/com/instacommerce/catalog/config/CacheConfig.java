package com.instacommerce.catalog.config;

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
                new CaffeineCache("products",
                        Caffeine.newBuilder()
                                .maximumSize(5000)
                                .expireAfterWrite(5, TimeUnit.MINUTES)
                                .build()),
                new CaffeineCache("categories",
                        Caffeine.newBuilder()
                                .maximumSize(500)
                                .expireAfterWrite(10, TimeUnit.MINUTES)
                                .build()),
                new CaffeineCache("search",
                        Caffeine.newBuilder()
                                .maximumSize(2000)
                                .expireAfterWrite(30, TimeUnit.SECONDS)
                                .build())
        ));
        return cacheManager;
    }
}
