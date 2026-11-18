package com.instacommerce.platform.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.Objects;

/**
 * Package-private adapter around a Caffeine {@link Cache} used as L1 near-cache storage.
 */
final class CaffeineL1Cache {
    private final Cache<Object, Object> cache;

    CaffeineL1Cache(Caffeine<Object, Object> builder) {
        this.cache = Objects.requireNonNull(builder, "Caffeine builder must not be null").build();
    }

    Object getIfPresent(Object key) {
        return key != null ? cache.getIfPresent(key) : null;
    }

    void put(Object key, Object value) {
        if (key == null) {
            return;
        }
        if (value != null) {
            cache.put(key, value);
        } else {
            cache.invalidate(key);
        }
    }

    void invalidate(Object key) {
        if (key != null) {
            cache.invalidate(key);
        }
    }

    void invalidateByPattern(String keyPattern) {
        if ("*".equals(keyPattern)) {
            cache.invalidateAll();
            return;
        }
        cache.invalidate(keyPattern);
        cache.asMap().keySet().removeIf(k -> keyPattern.equals(String.valueOf(k)));
    }

    void invalidateAll() {
        cache.invalidateAll();
    }

    Cache<Object, Object> nativeCache() {
        return cache;
    }
}
