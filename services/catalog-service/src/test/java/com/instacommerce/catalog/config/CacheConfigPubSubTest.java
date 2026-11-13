package com.instacommerce.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.catalog.config.CacheConfig.TwoLevelCacheManager;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.Subscription;
import org.springframework.data.redis.connection.SubscriptionListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("Catalog CacheConfig Pub/Sub Tests")
class CacheConfigPubSubTest {

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T instance) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(instance);
        return provider;
    }

    @Test
    @DisplayName("cacheManager operates cleanly when Redis beans are absent")
    void cacheManagerWithoutRedisWorksGracefully() {
        CacheConfig config = new CacheConfig();
        ReflectionTestUtils.setField(config, "redisEnabled", true);

        CacheManagerHolder holder = new CacheManagerHolder(
            config.cacheManager(providerOf(null), providerOf(null)));

        Cache products = holder.manager().getCache("products");
        UUID id = UUID.randomUUID();
        products.put(id, "Fresh Milk");

        assertThat(products.get(id, String.class)).isEqualTo("Fresh Milk");
        products.evict(id);
        assertThat(products.get(id)).isNull();
    }

    @Test
    @DisplayName("TwoLevelCache put and evict publish invalidation and peer L1 evicts via Redis Pub/Sub")
    void pubSubInvalidatesPeerL1CacheAcrossInstances() throws Exception {
        CountDownLatch subscribedA = new CountDownLatch(1);
        CountDownLatch subscribedB = new CountDownLatch(1);
        AtomicReference<MessageListener> listenerA = new AtomicReference<>();
        AtomicReference<MessageListener> listenerB = new AtomicReference<>();

        RedisConnectionFactory factoryA = mockSubscribedConnectionFactory(listenerA, subscribedA);
        RedisConnectionFactory factoryB = mockSubscribedConnectionFactory(listenerB, subscribedB);

        StringRedisTemplate templateA = mock(StringRedisTemplate.class);
        StringRedisTemplate templateB = mock(StringRedisTemplate.class);

        CacheConfig configA = new CacheConfig();
        ReflectionTestUtils.setField(configA, "redisEnabled", true);
        CacheConfig configB = new CacheConfig();
        ReflectionTestUtils.setField(configB, "redisEnabled", true);

        var managerA = configA.cacheManager(providerOf(factoryA), providerOf(templateA));
        var managerB = configB.cacheManager(providerOf(factoryB), providerOf(templateB));

        try {
            assertThat(subscribedA.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(subscribedB.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(listenerA.get()).isNotNull();
            assertThat(listenerB.get()).isNotNull();

            doAnswer(invocation -> {
                String channel = invocation.getArgument(0);
                String payload = invocation.getArgument(1);
                DefaultMessage msg = new DefaultMessage(
                    channel.getBytes(StandardCharsets.UTF_8),
                    payload.getBytes(StandardCharsets.UTF_8));
                listenerB.get().onMessage(msg, null);
                return null;
            }).when(templateA).convertAndSend(eq("catalog:cache:invalidate"), any(String.class));

            UUID productId = UUID.randomUUID();
            Cache cacheA = managerA.getCache("products");
            Cache cacheB = managerB.getCache("products");

            cacheB.put(productId, "Old Product");
            assertThat(cacheB.get(productId, String.class)).isEqualTo("Old Product");

            cacheA.put(productId, "Updated Product");
            verify(templateA).convertAndSend("catalog:cache:invalidate", "products:" + productId);
            awaitCacheEviction(cacheB, productId);

            cacheB.put(productId, "Stale Again");
            cacheA.evict(productId);
            awaitCacheEviction(cacheB, productId);

            String colonKey = "store-1:sku-42";
            cacheB.put(colonKey, "Regional Milk");
            cacheA.evict(colonKey);
            verify(templateA).convertAndSend("catalog:cache:invalidate", "products:" + colonKey);
            awaitCacheEviction(cacheB, colonKey);

            UUID otherId = UUID.randomUUID();
            cacheB.put(productId, "One");
            cacheB.put(otherId, "Two");
            clearInvocations(templateB);
            cacheA.clear();
            awaitCacheEviction(cacheB, productId);
            awaitCacheEviction(cacheB, otherId);

            verify(templateB, never()).convertAndSend(any(), any());
        } finally {
            destroyIfDisposable(managerA);
            destroyIfDisposable(managerB);
        }
    }

    @Test
    @DisplayName("TwoLevelCacheManager implements DisposableBean and closes listener subscription on destroy")
    void managerDestroyCleansUpSubscriber() throws Exception {
        CountDownLatch subscribed = new CountDownLatch(1);
        AtomicReference<MessageListener> listener = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean(false);
        RedisConnectionFactory factory = mockSubscribedConnectionFactory(listener, subscribed, closed);

        CacheConfig config = new CacheConfig();
        ReflectionTestUtils.setField(config, "redisEnabled", true);
        var manager = config.cacheManager(providerOf(factory), providerOf(mock(StringRedisTemplate.class)));

        assertThat(manager).isInstanceOf(TwoLevelCacheManager.class);
        assertThat(manager).isInstanceOf(DisposableBean.class);
        assertThat(subscribed.await(3, TimeUnit.SECONDS)).isTrue();

        ((DisposableBean) manager).destroy();
        assertThat(closed.get()).isTrue();
    }

    private RedisConnectionFactory mockSubscribedConnectionFactory(
            AtomicReference<MessageListener> capturedListener,
            CountDownLatch subscribedLatch) {
        return mockSubscribedConnectionFactory(capturedListener, subscribedLatch, new AtomicBoolean(false));
    }

    private RedisConnectionFactory mockSubscribedConnectionFactory(
            AtomicReference<MessageListener> capturedListener,
            CountDownLatch subscribedLatch,
            AtomicBoolean closed) {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        Subscription subscription = mock(Subscription.class);
        CountDownLatch closeLatch = new CountDownLatch(1);

        when(factory.getConnection()).thenReturn(connection);
        when(connection.getSubscription()).thenReturn(subscription);
        when(subscription.isAlive()).thenAnswer(inv -> !closed.get());
        doAnswer(invocation -> {
            closed.set(true);
            closeLatch.countDown();
            return null;
        }).when(subscription).close();
        doAnswer(invocation -> {
            closed.set(true);
            closeLatch.countDown();
            return null;
        }).when(subscription).unsubscribe();
        doAnswer(invocation -> {
            MessageListener listener = invocation.getArgument(0);
            capturedListener.set(listener);
            if (listener instanceof SubscriptionListener subListener) {
                Object[] args = invocation.getArguments();
                for (int i = 1; i < args.length; i++) {
                    if (args[i] instanceof byte[] channel) {
                        subListener.onChannelSubscribed(channel, 1L);
                    }
                }
            }
            subscribedLatch.countDown();
            closeLatch.await(5, TimeUnit.SECONDS);
            return null;
        }).when(connection).subscribe(any(MessageListener.class), any());
        doAnswer(invocation -> {
            closed.set(true);
            closeLatch.countDown();
            return null;
        }).when(connection).close();
        return factory;
    }

    private void awaitCacheEviction(Cache cache, Object key) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (cache.get(key) != null && System.nanoTime() < deadlineNanos) {
            Thread.sleep(5);
        }
        assertThat(cache.get(key)).isNull();
    }

    private void destroyIfDisposable(Object bean) throws Exception {
        if (bean instanceof DisposableBean disposable) {
            disposable.destroy();
        }
    }

    private record CacheManagerHolder(CacheManager manager) {
    }
}
