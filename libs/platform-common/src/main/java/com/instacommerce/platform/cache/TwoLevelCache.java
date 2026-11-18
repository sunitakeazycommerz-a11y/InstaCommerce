package com.instacommerce.platform.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Two-level Spring {@link Cache} combining a Caffeine L1 near-cache with an optional Redis L2 cache
 * and Pub/Sub cross-instance invalidation. Only explicit {@link #evict(Object)} and {@link #clear()}
 * publish invalidation messages so {@link #put(Object, Object)} never causes self-echo churn.
 */
public class TwoLevelCache implements Cache {
    private static final Logger log = LoggerFactory.getLogger(TwoLevelCache.class);

    private final String name;
    private final CaffeineL1Cache l1Cache;
    private final RedisCache l2RedisCache;
    private final StringRedisTemplate redisTemplate;
    private final String invalidationChannel;
    private final String instanceId;

    public TwoLevelCache(
            String name,
            Caffeine<Object, Object> l1Builder,
            RedisCache l2RedisCache,
            StringRedisTemplate redisTemplate,
            String invalidationChannel,
            String instanceId) {
        this.name = name;
        this.l1Cache = new CaffeineL1Cache(l1Builder);
        this.l2RedisCache = l2RedisCache;
        this.redisTemplate = redisTemplate;
        this.invalidationChannel = invalidationChannel;
        this.instanceId = instanceId;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getNativeCache() {
        return l2RedisCache != null ? l2RedisCache.getNativeCache() : l1Cache.nativeCache();
    }

    @Override
    public ValueWrapper get(Object key) {
        Object l1Val = l1Cache.getIfPresent(key);
        if (l1Val != null) {
            return new SimpleValueWrapper(l1Val);
        }
        if (l2RedisCache != null) {
            try {
                ValueWrapper l2Val = l2RedisCache.get(key);
                if (l2Val != null && l2Val.get() != null) {
                    l1Cache.put(key, l2Val.get());
                    return l2Val;
                }
            } catch (Exception e) {
                log.debug("Redis cache get error in cache {} for key {}: {}", name, key, e.getMessage());
            }
        }
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Class<T> type) {
        ValueWrapper wrapper = get(key);
        if (wrapper != null && wrapper.get() != null) {
            Object val = wrapper.get();
            if (type == null || type.isInstance(val)) {
                return (T) val;
            }
        }
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        ValueWrapper wrapper = get(key);
        if (wrapper != null) {
            return (T) wrapper.get();
        }
        try {
            T value = valueLoader.call();
            put(key, value);
            return value;
        } catch (Exception e) {
            throw new ValueRetrievalException(key, valueLoader, e);
        }
    }

    @Override
    public void put(Object key, Object value) {
        l1Cache.put(key, value);
        if (l2RedisCache != null) {
            try {
                l2RedisCache.put(key, value);
            } catch (Exception e) {
                log.debug("Redis cache put error in cache {} for key {}: {}", name, key, e.getMessage());
            }
        }
    }

    public void evictLocal(String keyPattern) {
        l1Cache.invalidateByPattern(keyPattern);
    }

    @Override
    public void evict(Object key) {
        l1Cache.invalidate(key);
        if (l2RedisCache != null) {
            try {
                l2RedisCache.evict(key);
            } catch (Exception e) {
                log.debug("Redis cache evict error in cache {} for key {}: {}", name, key, e.getMessage());
            }
        }
        publishInvalidation(key != null ? key.toString() : "*");
    }

    @Override
    public void clear() {
        l1Cache.invalidateAll();
        if (l2RedisCache != null) {
            try {
                l2RedisCache.clear();
            } catch (Exception e) {
                log.debug("Redis cache clear error in cache {}: {}", name, e.getMessage());
            }
        }
        publishInvalidation("*");
    }

    private void publishInvalidation(String keyPattern) {
        if (redisTemplate != null && invalidationChannel != null && !invalidationChannel.isBlank()) {
            try {
                String prefix = instanceId != null ? instanceId + "|" : "";
                redisTemplate.convertAndSend(invalidationChannel, prefix + name + ":" + keyPattern);
            } catch (Exception e) {
                log.debug("Failed to publish cache invalidation in cache {}: {}", name, e.getMessage());
            }
        }
    }
}
