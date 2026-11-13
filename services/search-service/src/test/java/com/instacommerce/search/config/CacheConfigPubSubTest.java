package com.instacommerce.search.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.search.config.CacheConfig.TwoLevelCacheManager;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

@DisplayName("Search CacheConfig Pub/Sub Tests")
class CacheConfigPubSubTest {

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T instance) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(instance);
        return provider;
    }

    @Test
    @DisplayName("cacheManager works gracefully when Redis beans are absent")
    void cacheManagerWithoutRedisWorksGracefully() {
        CacheConfig config = new CacheConfig();
        ReflectionTestUtils.setField(config, "redisEnabled", true);

        CacheManager manager = config.cacheManager(providerOf(null), providerOf(null));
        Cache trending = manager.getCache("trending");
        trending.put(10, List.of("milk", "eggs"));

        assertThat(trending.get(10)).isNotNull();
        trending.evict(10);
        assertThat(trending.get(10)).isNull();
    }

    @Test
    @DisplayName("TwoLevelCache put and evict publish to search:cache:invalidate and evict typed peer L1 keys")
    void pubSubInvalidatesPeerL1CacheAcrossInstances() throws Exception {
        CountDownLatch subscribedA = new CountDownLatch(1);
        CountDownLatch subscribedB = new CountDownLatch(1);
        AtomicReference<MessageListener> listenerA = new AtomicReference<>();
        AtomicReference<MessageListener> listenerB = new AtomicReference<>();

        RedisConnectionFactory factoryA = mockSubscribedConnectionFactory(listenerA, subscribedA, new AtomicBoolean(false));
        RedisConnectionFactory factoryB = mockSubscribedConnectionFactory(listenerB, subscribedB, new AtomicBoolean(false));

        StringRedisTemplate templateA = mock(StringRedisTemplate.class);
        StringRedisTemplate templateB = mock(StringRedisTemplate.class);

        CacheConfig configA = new CacheConfig();
        ReflectionTestUtils.setField(configA, "redisEnabled", true);
        CacheConfig configB = new CacheConfig();
        ReflectionTestUtils.setField(configB, "redisEnabled", true);

        CacheManager managerA = configA.cacheManager(providerOf(factoryA), providerOf(templateA));
        CacheManager managerB = configB.cacheManager(providerOf(factoryB), providerOf(templateB));

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
                listenerA.get().onMessage(msg, null);
                listenerB.get().onMessage(msg, null);
                return null;
            }).when(templateA).convertAndSend(eq("search:cache:invalidate"), any(String.class));

            Cache trendingA = managerA.getCache("trending");
            Cache trendingB = managerB.getCache("trending");
            trendingB.put(10, List.of("old-milk"));
            assertThat(trendingB.get(10)).isNotNull();

            trendingA.put(10, List.of("fresh-milk"));
            verify(templateA).convertAndSend(eq("search:cache:invalidate"),
                argThat((String msg) -> msg.indexOf('|') > 0 && msg.endsWith("|trending:10")));
            assertThat(trendingA.get(10)).isNotNull();
            assertThat(trendingA.get(10).get()).isEqualTo(List.of("fresh-milk"));
            awaitCacheEviction(trendingB, 10);

            Cache resultsA = managerA.getCache("searchResults");
            Cache resultsB = managerB.getCache("searchResults");
            String colonKey = "store-1:milk:0";
            resultsB.put(colonKey, "cached-page");
            resultsA.evict(colonKey);
            verify(templateA).convertAndSend(eq("search:cache:invalidate"),
                argThat((String msg) -> msg.indexOf('|') > 0 && msg.endsWith("|searchResults:" + colonKey)));
            awaitCacheEviction(resultsB, colonKey);

            String pipeKey = "milk|dairy:0";
            resultsB.put(pipeKey, "legacy-page");
            listenerB.get().onMessage(new DefaultMessage(
                "search:cache:invalidate".getBytes(StandardCharsets.UTF_8),
                ("searchResults:" + pipeKey).getBytes(StandardCharsets.UTF_8)), null);
            awaitCacheEviction(resultsB, pipeKey);

            resultsB.put("a", "1");
            resultsB.put("b", "2");
            clearInvocations(templateB);
            resultsA.clear();
            awaitCacheEviction(resultsB, "a");
            awaitCacheEviction(resultsB, "b");

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
        CacheManager manager = config.cacheManager(providerOf(factory), providerOf(mock(StringRedisTemplate.class)));

        assertThat(manager).isInstanceOf(TwoLevelCacheManager.class);
        assertThat(manager).isInstanceOf(DisposableBean.class);
        assertThat(subscribed.await(3, TimeUnit.SECONDS)).isTrue();

        ((DisposableBean) manager).destroy();
        assertThat(closed.get()).isTrue();
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
}
