package com.instacommerce.catalog.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
@EnableCaching
public class CacheConfig {

    public static final String INVALIDATION_CHANNEL = "catalog:cache:invalidate";
    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Value("${catalog.cache.redis.enabled:true}")
    private boolean redisEnabled;

    @Bean
    @Primary
    public CacheManager cacheManager(
            ObjectProvider<RedisConnectionFactory> connectionFactoryProvider,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider) {

        RedisConnectionFactory connectionFactory = connectionFactoryProvider.getIfAvailable();
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();

        RedisCacheManager redisCacheManager = null;
        if (redisEnabled && connectionFactory != null) {
            try {
                RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                        .prefixCacheNameWith("catalog:")
                        .entryTtl(Duration.ofMinutes(5))
                        .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                        .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()));

                Map<String, RedisCacheConfiguration> initialConfigs = new HashMap<>();
                initialConfigs.put("products", defaultConfig.entryTtl(Duration.ofMinutes(5)));
                initialConfigs.put("categories", defaultConfig.entryTtl(Duration.ofMinutes(10)));
                initialConfigs.put("search", defaultConfig.entryTtl(Duration.ofMinutes(1)));

                redisCacheManager = RedisCacheManager.builder(connectionFactory)
                        .cacheDefaults(defaultConfig)
                        .withInitialCacheConfigurations(initialConfigs)
                        .build();
                log.info("Initialized distributed RedisCacheManager for catalog-service");
            } catch (Exception e) {
                log.warn("Failed to initialize RedisCacheManager, falling back to L1 Caffeine near-cache: {}", e.getMessage());
            }
        }

        return new TwoLevelCacheManager(redisCacheManager, redisTemplate, redisEnabled ? connectionFactory : null);
    }

    public static class TwoLevelCacheManager implements CacheManager, MessageListener, DisposableBean {
        private final RedisCacheManager redisCacheManager;
        private final StringRedisTemplate redisTemplate;
        private final RedisMessageListenerContainer listenerContainer;
        private final String instanceId = UUID.randomUUID().toString();
        private final Map<String, Cache> caches = new ConcurrentHashMap<>();

        public TwoLevelCacheManager(RedisCacheManager redisCacheManager, StringRedisTemplate redisTemplate) {
            this(redisCacheManager, redisTemplate, null);
        }

        public TwoLevelCacheManager(
                RedisCacheManager redisCacheManager,
                StringRedisTemplate redisTemplate,
                RedisConnectionFactory connectionFactory) {
            this.redisCacheManager = redisCacheManager;
            this.redisTemplate = redisTemplate;
            this.listenerContainer = initListenerContainer(connectionFactory);
        }

        private RedisMessageListenerContainer initListenerContainer(RedisConnectionFactory connectionFactory) {
            if (connectionFactory == null) {
                return null;
            }
            RedisMessageListenerContainer container = new RedisMessageListenerContainer();
            try {
                container.setConnectionFactory(connectionFactory);
                container.addMessageListener(this, ChannelTopic.of(INVALIDATION_CHANNEL));
                container.afterPropertiesSet();
                container.start();
                log.info("Subscribed to Redis cache invalidation channel {}", INVALIDATION_CHANNEL);
                return container;
            } catch (Exception e) {
                log.warn("Failed to start Redis cache invalidation subscriber: {}", e.getMessage());
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
        public void destroy() {
            stopAndDestroyContainer(listenerContainer);
        }

        @Override
        public Cache getCache(String name) {
            return caches.computeIfAbsent(name, n -> {
                RedisCache redisCache = redisCacheManager != null ? (RedisCache) redisCacheManager.getCache(n) : null;
                long l1TtlSeconds = switch (n) {
                    case "categories" -> 30;
                    case "search" -> 10;
                    default -> 15;
                };
                com.github.benmanes.caffeine.cache.Cache<Object, Object> l1 = Caffeine.newBuilder()
                        .maximumSize(5000)
                        .expireAfterWrite(l1TtlSeconds, TimeUnit.SECONDS)
                        .build();
                return new TwoLevelCache(n, l1, redisCache, redisTemplate, instanceId);
            });
        }

        @Override
        public Collection<String> getCacheNames() {
            return caches.keySet();
        }
    }

    public static class TwoLevelCache implements Cache {
        private static final Logger logger = LoggerFactory.getLogger(TwoLevelCache.class);

        private final String name;
        private final com.github.benmanes.caffeine.cache.Cache<Object, Object> l1Cache;
        private final RedisCache l2RedisCache;
        private final StringRedisTemplate redisTemplate;
        private final String instanceId;

        public TwoLevelCache(String name,
                             com.github.benmanes.caffeine.cache.Cache<Object, Object> l1Cache,
                             RedisCache l2RedisCache,
                             StringRedisTemplate redisTemplate) {
            this(name, l1Cache, l2RedisCache, redisTemplate, null);
        }

        public TwoLevelCache(String name,
                             com.github.benmanes.caffeine.cache.Cache<Object, Object> l1Cache,
                             RedisCache l2RedisCache,
                             StringRedisTemplate redisTemplate,
                             String instanceId) {
            this.name = name;
            this.l1Cache = l1Cache;
            this.l2RedisCache = l2RedisCache;
            this.redisTemplate = redisTemplate;
            this.instanceId = instanceId;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Object getNativeCache() {
            return l2RedisCache != null ? l2RedisCache.getNativeCache() : l1Cache;
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
                    logger.debug("Redis cache get error for key {}: {}", key, e.getMessage());
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
            if (value != null) {
                l1Cache.put(key, value);
            } else {
                l1Cache.invalidate(key);
            }
            if (l2RedisCache != null) {
                try {
                    l2RedisCache.put(key, value);
                } catch (Exception e) {
                    logger.debug("Redis cache put error for key {}: {}", key, e.getMessage());
                }
            }
            publishInvalidation(key != null ? key.toString() : "*");
        }

        void evictLocal(String keyPattern) {
            if ("*".equals(keyPattern)) {
                l1Cache.invalidateAll();
                return;
            }
            l1Cache.invalidate(keyPattern);
            l1Cache.asMap().keySet().removeIf(k -> keyPattern.equals(String.valueOf(k)));
        }

        @Override
        public void evict(Object key) {
            l1Cache.invalidate(key);
            if (l2RedisCache != null) {
                try {
                    l2RedisCache.evict(key);
                } catch (Exception e) {
                    logger.debug("Redis cache evict error for key {}: {}", key, e.getMessage());
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
                    logger.debug("Redis cache clear error: {}", e.getMessage());
                }
            }
            publishInvalidation("*");
        }

        private void publishInvalidation(String keyPattern) {
            if (redisTemplate != null) {
                try {
                    String prefix = instanceId != null ? instanceId + "|" : "";
                    redisTemplate.convertAndSend(INVALIDATION_CHANNEL, prefix + name + ":" + keyPattern);
                } catch (Exception e) {
                    logger.debug("Failed to publish cache invalidation message: {}", e.getMessage());
                }
            }
        }
    }
}
