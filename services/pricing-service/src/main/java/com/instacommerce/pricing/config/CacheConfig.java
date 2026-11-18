package com.instacommerce.pricing.config;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnection;
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

    public static final String INVALIDATION_CHANNEL = "pricing:cache:invalidate";
    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Value("${pricing.cache.redis.enabled:true}")
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
                        .prefixCacheNameWith("pricing:")
                        .entryTtl(Duration.ofMinutes(5))
                        .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                        .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(jsonRedisSerializer()));

                Map<String, RedisCacheConfiguration> initialConfigs = new HashMap<>();
                initialConfigs.put("productPrices", defaultConfig.entryTtl(Duration.ofMinutes(5)));
                initialConfigs.put("activePromotions", defaultConfig.entryTtl(Duration.ofMinutes(2)));
                initialConfigs.put("priceRules", defaultConfig.entryTtl(Duration.ofMinutes(5)));

                redisCacheManager = RedisCacheManager.builder(connectionFactory)
                        .cacheDefaults(defaultConfig)
                        .withInitialCacheConfigurations(initialConfigs)
                        .build();
                log.info("Initialized distributed RedisCacheManager for pricing-service");
            } catch (Exception e) {
                log.warn("Failed to initialize RedisCacheManager in pricing-service, falling back to L1 Caffeine near-cache: {}", e.getMessage());
            }
        }

        return new TwoLevelCacheManager(redisCacheManager, redisTemplate);
    }

    @Bean
    @ConditionalOnBean(RedisConnectionFactory.class)
    @ConditionalOnProperty(name = "pricing.cache.redis.enabled", havingValue = "true", matchIfMissing = true)
    public RedisMessageListenerContainer cacheInvalidationListenerContainer(
            RedisConnectionFactory connectionFactory,
            CacheManager cacheManager) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setAutoStartup(false);
        if (cacheManager instanceof MessageListener listener) {
            container.addMessageListener(listener, ChannelTopic.of(INVALIDATION_CHANNEL));
        }
        return container;
    }

    @Bean
    @ConditionalOnBean(RedisMessageListenerContainer.class)
    public ApplicationListener<ApplicationReadyEvent> cacheInvalidationListenerStarter(
            RedisMessageListenerContainer container,
            RedisConnectionFactory connectionFactory) {
        return event -> {
            try (RedisConnection connection = connectionFactory.getConnection()) {
                connection.ping();
                container.start();
                log.info("Subscribed to Redis cache invalidation channel {}", INVALIDATION_CHANNEL);
            } catch (Exception e) {
                log.warn("Redis unavailable at startup; skipping Pub/Sub listener start in pricing-service: {}", e.getMessage());
            }
        };
    }

    @SuppressWarnings({"deprecation", "removal"})
    static GenericJackson2JsonRedisSerializer jsonRedisSerializer() {
        return new GenericJackson2JsonRedisSerializer().configure(objectMapper -> {
            objectMapper.registerModule(new JavaTimeModule());
            objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        });
    }

    public static class TwoLevelCacheManager implements CacheManager, MessageListener {
        private final RedisCacheManager redisCacheManager;
        private final StringRedisTemplate redisTemplate;
        private final String instanceId = UUID.randomUUID().toString();
        private final Map<String, Cache> caches = new ConcurrentHashMap<>();

        public TwoLevelCacheManager(RedisCacheManager redisCacheManager, StringRedisTemplate redisTemplate) {
            this.redisCacheManager = redisCacheManager;
            this.redisTemplate = redisTemplate;
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
                long l1TtlSeconds = switch (n) {
                    case "activePromotions" -> 30;
                    case "priceRules" -> 15;
                    default -> 15;
                };
                LoadingCache<Object, Object> l1 = Caffeine.newBuilder()
                        .maximumSize(10000)
                        .expireAfterWrite(l1TtlSeconds, TimeUnit.SECONDS)
                        .build(key -> null);
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
        private final LoadingCache<Object, Object> l1Cache;
        private final RedisCache l2RedisCache;
        private final StringRedisTemplate redisTemplate;
        private final String instanceId;

        public TwoLevelCache(String name,
                             LoadingCache<Object, Object> l1Cache,
                             RedisCache l2RedisCache,
                             StringRedisTemplate redisTemplate) {
            this(name, l1Cache, l2RedisCache, redisTemplate, null);
        }

        public TwoLevelCache(String name,
                             LoadingCache<Object, Object> l1Cache,
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
                    logger.debug("Redis cache get error in pricing-service for key {}: {}", key, e.getMessage());
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
                    logger.debug("Redis cache put error in pricing-service for key {}: {}", key, e.getMessage());
                }
            }
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
                    logger.debug("Redis cache evict error in pricing-service for key {}: {}", key, e.getMessage());
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
                    logger.debug("Redis cache clear error in pricing-service: {}", e.getMessage());
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
                    logger.debug("Failed to publish cache invalidation message in pricing-service: {}", e.getMessage());
                }
            }
        }
    }
}
