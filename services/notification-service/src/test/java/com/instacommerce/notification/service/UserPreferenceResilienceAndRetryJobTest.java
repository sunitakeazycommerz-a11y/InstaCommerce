package com.instacommerce.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.instacommerce.notification.config.NotificationProperties;
import com.instacommerce.notification.domain.model.NotificationChannel;
import com.instacommerce.notification.domain.model.NotificationLog;
import com.instacommerce.notification.infrastructure.retry.NotificationRetryJob;
import com.instacommerce.notification.infrastructure.retry.RetryableNotificationSender;
import com.instacommerce.notification.repository.NotificationLogRepository;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot3.retry.autoconfigure.RetryAutoConfiguration;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;

class UserPreferenceResilienceAndRetryJobTest {

    @Test
    @DisplayName("UserPreferenceService circuit breaker and retry fall back to fail-open transactional prefs without caching")
    void preferenceCircuitBreakerFallsBackFailOpenAndDoesNotCacheFallback() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                AopAutoConfiguration.class,
                CircuitBreakerAutoConfiguration.class,
                RetryAutoConfiguration.class))
            .withBean(NotificationProperties.class, () -> {
                NotificationProperties props = new NotificationProperties();
                // Unreachable closed port triggers immediate ResourceAccessException
                props.getIdentity().setBaseUrl("http://127.0.0.1:1");
                props.getIdentity().setPreferenceCacheTtl(Duration.ofMinutes(5));
                return props;
            })
            .withBean(Clock.class, () -> Clock.systemUTC())
            .withBean(UserPreferenceService.class)
            .withPropertyValues(
                "spring.application.name=notification-service",
                "internal.service.token=test-token",
                "resilience4j.retry.instances.identityService.maxAttempts=2",
                "resilience4j.retry.instances.identityService.waitDuration=1ms",
                "resilience4j.retry.instances.identityService.retryExceptions="
                    + "com.instacommerce.notification.service.PreferenceLookupException,"
                    + "org.springframework.web.client.ResourceAccessException",
                "resilience4j.circuitbreaker.instances.identityService.slidingWindowSize=4",
                "resilience4j.circuitbreaker.instances.identityService.minimumNumberOfCalls=2"
            );

        runner.run(context -> {
            assertThat(context).hasSingleBean(UserPreferenceService.class);
            UserPreferenceService service = context.getBean(UserPreferenceService.class);
            UUID userId = UUID.randomUUID();

            UserPreferenceService.Preferences fallback = service.getPreferences(userId);
            assertThat(fallback.userId()).isEqualTo(userId);
            assertThat(fallback.emailOptOut()).isFalse();
            assertThat(fallback.smsOptOut()).isFalse();
            assertThat(fallback.pushOptOut()).isFalse();
            assertThat(fallback.marketingOptOut()).isTrue();
            assertThat(service.allowNotification(fallback, NotificationChannel.PUSH, false)).isTrue();
            assertThat(service.allowNotification(fallback, NotificationChannel.EMAIL, true)).isFalse();
        });
    }

    @Test
    @DisplayName("NotificationRetryJob claims batch in short transaction, extends ShedLock, and dispatches outside TX")
    @SuppressWarnings("unchecked")
    void retryJobClaimsInShortTxAndDispatchesOutsideTransaction() throws Exception {
        Method method = NotificationRetryJob.class.getMethod("retryPendingNotifications");
        assertThat(method.getAnnotation(Transactional.class)).isNull();
        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);
        assertThat(lock).isNotNull();
        assertThat(lock.lockAtMostFor()).isEqualTo("PT5M");

        NotificationLogRepository logRepository = mock(NotificationLogRepository.class);
        RetryableNotificationSender sender = mock(RetryableNotificationSender.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        ObjectProvider<PlatformTransactionManager> txProvider = mock(ObjectProvider.class);
        ObjectProvider<Clock> clockProvider = mock(ObjectProvider.class);

        Clock fixedClock = Clock.fixed(Instant.parse("2026-03-20T10:00:00Z"), ZoneOffset.UTC);
        when(txProvider.getIfAvailable()).thenReturn(txManager);
        when(clockProvider.getIfAvailable()).thenReturn(fixedClock);

        TransactionStatus txStatus = new SimpleTransactionStatus();
        when(txManager.getTransaction(any(TransactionDefinition.class))).thenReturn(txStatus);

        NotificationLog log = new NotificationLog();
        log.setId(UUID.randomUUID());
        log.setNextRetryAt(Instant.parse("2026-03-20T09:59:00Z"));
        when(logRepository.findPendingForRetry(any(), anyInt())).thenReturn(List.of(log));

        NotificationRetryJob job = new NotificationRetryJob(
            logRepository, sender, new NotificationProperties(), txProvider, clockProvider);

        job.retryPendingNotifications();

        assertThat(log.getNextRetryAt()).isEqualTo(Instant.parse("2026-03-20T10:05:00Z"));
        var order = inOrder(txManager, logRepository, sender);
        order.verify(txManager).getTransaction(any(TransactionDefinition.class));
        order.verify(logRepository).findPendingForRetry(Instant.parse("2026-03-20T10:00:00Z"), 100);
        order.verify(logRepository).saveAll(List.of(log));
        order.verify(txManager).commit(txStatus);
        order.verify(sender).retry(log);
    }
}
