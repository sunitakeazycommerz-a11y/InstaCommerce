package com.instacommerce.featureflag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.featureflag.config.CacheConfig;
import com.instacommerce.featureflag.config.FlagCacheEventListener;
import com.instacommerce.featureflag.config.FlagCacheInvalidator;
import com.instacommerce.featureflag.controller.FlagChangeStreamController;
import com.instacommerce.featureflag.domain.model.FeatureFlag;
import com.instacommerce.featureflag.domain.model.FlagType;
import com.instacommerce.featureflag.dto.request.CreateFlagRequest;
import com.instacommerce.featureflag.repository.FeatureFlagRepository;
import com.instacommerce.featureflag.repository.FlagAuditLogRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class FeatureFlagEdgeFixesTest {

    @AfterEach
    void cleanupTxSync() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("FlagManagementService defers Redis pub/sub and SSE broadcast until afterCommit and evicts caches on createFlag")
    void managementServicePublishesAfterCommitAndEvictsOnCreate() throws Exception {
        FeatureFlagRepository flagRepository = mock(FeatureFlagRepository.class);
        FlagAuditLogRepository auditLogRepository = mock(FlagAuditLogRepository.class);
        FlagChangeStreamController streamController = mock(FlagChangeStreamController.class);
        FlagCacheEventListener cacheEventListener = mock(FlagCacheEventListener.class);
        CacheManager cacheManager = new ConcurrentMapCacheManager("flags", "flags-all", "flag-overrides", "flag-overrides-bulk");

        FlagManagementService service = new FlagManagementService(
                flagRepository, auditLogRepository, streamController, cacheManager, cacheEventListener);

        Method createMethod = FlagManagementService.class.getMethod("createFlag", CreateFlagRequest.class, String.class);
        Caching caching = createMethod.getAnnotation(Caching.class);
        assertThat(caching).isNotNull();
        assertThat(caching.evict()).extracting(CacheEvict::value)
                .anySatisfy(arr -> assertThat(arr).contains("flags"))
                .anySatisfy(arr -> assertThat(arr).contains("flags-all"));

        UUID id = UUID.randomUUID();
        when(flagRepository.findByKey("new-flag")).thenReturn(Optional.empty());
        when(flagRepository.save(any(FeatureFlag.class))).thenAnswer(inv -> {
            FeatureFlag f = inv.getArgument(0);
            f.setId(id);
            return f;
        });

        TransactionSynchronizationManager.initSynchronization();
        service.createFlag(
                new CreateFlagRequest("new-flag", "New Flag", "desc", FlagType.BOOLEAN, true, "true", 100, null, null, null),
                "admin-user");

        verify(cacheEventListener, never()).publishFlagUpdate(any(), any(), any());
        verify(streamController, never()).broadcast(any(), any());

        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }

        verify(cacheEventListener, times(1)).publishFlagUpdate(eq(id), eq("new-flag"), any());
        verify(streamController, times(1)).broadcast("new-flag", true);
    }

    @Test
    @DisplayName("FlagCacheRefreshJob runs per-pod without @SchedulerLock")
    void refreshJobHasNoSchedulerLockAnnotation() throws Exception {
        Method refreshMethod = FlagCacheRefreshJob.class.getMethod("refreshCache");
        assertThat(refreshMethod.getAnnotation(SchedulerLock.class)).isNull();
    }

    @Test
    @DisplayName("BulkEvaluationService reads flags via cached FlagEvaluationService.loadAllFlags()")
    void bulkEvaluationUsesCachedLoadAllFlags() {
        FlagEvaluationService evaluationService = mock(FlagEvaluationService.class);
        FlagOverrideService overrideService = mock(FlagOverrideService.class);
        FeatureFlagRepository flagRepository = mock(FeatureFlagRepository.class);
        BulkEvaluationService bulkService = new BulkEvaluationService(evaluationService, overrideService, flagRepository);

        FeatureFlag flag = new FeatureFlag();
        flag.setId(UUID.randomUUID());
        flag.setKey("fast-checkout");
        when(evaluationService.loadAllFlags()).thenReturn(List.of(flag));
        when(evaluationService.evaluate(eq(flag), eq("fast-checkout"), any(), any(), any()))
                .thenReturn(new com.instacommerce.featureflag.dto.response.FlagEvaluationResponse(
                        "fast-checkout", true, "DEFAULT"));

        Map<String, Object> result = bulkService.evaluateAllActiveFlags(UUID.randomUUID().toString(), Map.of());

        assertThat(result).containsEntry("fast-checkout", true);
        verify(evaluationService).loadAllFlags();
        verify(flagRepository, never()).findAll();
    }

    @Test
    @DisplayName("FlagCacheInvalidator evicts flags-all and bridges Redis updates to SSE broadcast")
    void cacheInvalidatorBridgesUpdatesToSseAndClearsFlagsAll() {
        RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
        CacheManager cacheManager = new ConcurrentMapCacheManager("flags", "flags-all", "flag-overrides", "flag-overrides-bulk");
        FlagChangeStreamController streamController = mock(FlagChangeStreamController.class);

        cacheManager.getCache("flags").put("checkout-v2", "old");
        cacheManager.getCache("flags-all").put("all", List.of("stale"));

        FlagCacheInvalidator invalidator = new FlagCacheInvalidator(
                container, cacheManager, new SimpleMeterRegistry(), streamController);

        String payload = "{\"flagKey\":\"checkout-v2\",\"value\":\"enabled=true\"}";
        invalidator.onMessage(
                new DefaultMessage("flag-updates:checkout-v2".getBytes(StandardCharsets.UTF_8),
                        payload.getBytes(StandardCharsets.UTF_8)),
                null);

        assertThat(cacheManager.getCache("flags").get("checkout-v2")).isNull();
        assertThat(cacheManager.getCache("flags-all").get("all")).isNull();
        verify(streamController).broadcast("checkout-v2", "enabled=true");
    }

    @Test
    @DisplayName("CacheConfig conditionally guards RedisTemplate and RedisMessageListenerContainer")
    void cacheConfigHasConditionalOnPropertyForRedisBeans() throws Exception {
        Method redisTemplateMethod = CacheConfig.class.getMethod(
                "redisTemplate", org.springframework.data.redis.connection.RedisConnectionFactory.class);
        Method listenerMethod = CacheConfig.class.getMethod(
                "redisMessageListenerContainer",
                org.springframework.data.redis.connection.RedisConnectionFactory.class,
                io.micrometer.core.instrument.MeterRegistry.class);

        var tplCond = redisTemplateMethod.getAnnotation(
                org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class);
        var listenerCond = listenerMethod.getAnnotation(
                org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class);

        assertThat(tplCond).isNotNull();
        assertThat(tplCond.name()).contains("feature-flag.cache.redis-enabled");
        assertThat(listenerCond).isNotNull();
        assertThat(listenerCond.name()).contains("feature-flag.cache.redis-enabled");
    }
}
