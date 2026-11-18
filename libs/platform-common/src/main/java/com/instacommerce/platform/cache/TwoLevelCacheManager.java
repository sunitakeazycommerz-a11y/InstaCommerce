package com.instacommerce.platform.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Spring {@link CacheManager} coordinating Caffeine L1 near-caches and Redis L2 caches with
 * Pub/Sub invalidation and clean {@link DisposableBean} listener teardown.
 */
public class TwoLevelCacheManager implements CacheManager, MessageListener, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(TwoLevelCacheManager.class);

    private final RedisCacheManager redisCacheManager;
    private final StringRedisTemplate redisTemplate;
    private final String invalidationChannel;
    private final Function<String, Caffeine<Object, Object>> l1BuilderFactory;
    private final RedisMessageListenerContainer listenerContainer;
    private final String instanceId = UUID.randomUUID().toString();
    private final Map<String, Cache> caches = new ConcurrentHashMap<>();

    public TwoLevelCacheManager(
            RedisCacheManager redisCacheManager,
            StringRedisTemplate redisTemplate,
            String invalidationChannel,
            Function<String, Caffeine<Object, Object>> l1BuilderFactory) {
        this(redisCacheManager, redisTemplate, null, invalidationChannel, l1BuilderFactory);
    }

    public TwoLevelCacheManager(
            RedisCacheManager redisCacheManager,
            StringRedisTemplate redisTemplate,
            RedisConnectionFactory connectionFactory,
            String invalidationChannel,
            Function<String, Caffeine<Object, Object>> l1BuilderFactory) {
        this.redisCacheManager = redisCacheManager;
        this.redisTemplate = redisTemplate;
        this.invalidationChannel = invalidationChannel;
        this.l1BuilderFactory = Objects.requireNonNull(l1BuilderFactory, "l1BuilderFactory must not be null");
        this.listenerContainer = initListenerContainer(connectionFactory);
    }

    private RedisMessageListenerContainer initListenerContainer(RedisConnectionFactory connectionFactory) {
        if (connectionFactory == null || invalidationChannel == null || invalidationChannel.isBlank()) {
            return null;
        }
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        try {
            container.setConnectionFactory(connectionFactory);
            container.addMessageListener(this, ChannelTopic.of(invalidationChannel));
            container.afterPropertiesSet();
            container.start();
            log.info("Subscribed to Redis cache invalidation channel {}", invalidationChannel);
            return container;
        } catch (Exception e) {
            log.warn("Failed to start Redis cache invalidation subscriber on {}: {}",
                    invalidationChannel, e.getMessage());
            stopAndDestroyContainer(container);
            return null;
        }
    }

    private void stopAndDestroyContainer(RedisMessageListenerContainer container) {
        if (container == null) {
            return;
        }
        try {
            container.stop();
        } catch (Exception e) {
            log.debug("Error stopping Redis cache invalidation listener container: {}", e.getMessage());
        } finally {
            try {
                container.destroy();
            } catch (Exception e) {
                log.debug("Error destroying Redis cache invalidation listener container: {}", e.getMessage());
            }
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        if (message == null || message.getBody() == null) {
            return;
        }
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        int colonIdx = payload.indexOf(':');
        int originIdx = payload.indexOf('|');
        if (originIdx > 0 && originIdx < colonIdx) {
            if (instanceId.equals(payload.substring(0, originIdx))) {
                return;
            }
            payload = payload.substring(originIdx + 1);
            colonIdx = payload.indexOf(':');
        }
        if (colonIdx <= 0 || colonIdx >= payload.length() - 1) {
            return;
        }
        String cacheName = payload.substring(0, colonIdx);
        String keyPattern = payload.substring(colonIdx + 1);
        Cache cache = caches.get(cacheName);
        if (cache instanceof TwoLevelCache twoLevelCache) {
            twoLevelCache.evictLocal(keyPattern);
        }
    }

    @Override
    public Cache getCache(String name) {
        return caches.computeIfAbsent(name, n -> {
            RedisCache redisCache = redisCacheManager != null ? (RedisCache) redisCacheManager.getCache(n) : null;
            return new TwoLevelCache(n, l1BuilderFactory.apply(n), redisCache, redisTemplate,
                    invalidationChannel, instanceId);
        });
    }

    @Override
    public Collection<String> getCacheNames() {
        return Collections.unmodifiableSet(caches.keySet());
    }

    @Override
    public void destroy() {
        stopAndDestroyContainer(listenerContainer);
    }
}
