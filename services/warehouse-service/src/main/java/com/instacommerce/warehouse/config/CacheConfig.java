package com.instacommerce.warehouse.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("stores", "store-zones", "store-hours");
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(2000)
                .expireAfterWrite(Duration.ofMinutes(5))
                .recordStats());
        return cacheManager;
    }
}
