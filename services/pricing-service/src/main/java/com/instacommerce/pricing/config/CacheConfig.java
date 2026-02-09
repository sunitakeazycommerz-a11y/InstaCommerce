package com.instacommerce.pricing.config;

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
                new CaffeineCache("productPrices",
                        Caffeine.newBuilder()
                                .maximumSize(10000)
                                .expireAfterWrite(30, TimeUnit.SECONDS)
                                .build()),
                new CaffeineCache("activePromotions",
                        Caffeine.newBuilder()
                                .maximumSize(500)
                                .expireAfterWrite(60, TimeUnit.SECONDS)
                                .build()),
                new CaffeineCache("priceRules",
                        Caffeine.newBuilder()
                                .maximumSize(10000)
                                .expireAfterWrite(30, TimeUnit.SECONDS)
                                .build())
        ));
        return cacheManager;
    }
}
