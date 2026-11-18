package com.instacommerce.platform.cache;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * Shared JSON Redis value serializer configured with {@link JavaTimeModule} and ISO-8601
 * timestamps for services whose cached DTOs use Jackson 2 annotations.
 */
public final class CacheJsonSerializer {

    private CacheJsonSerializer() {
    }

    @SuppressWarnings({"deprecation", "removal"})
    public static RedisSerializer<Object> create() {
        return new GenericJackson2JsonRedisSerializer().configure(objectMapper -> {
            objectMapper.registerModule(new JavaTimeModule());
            objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        });
    }
}
