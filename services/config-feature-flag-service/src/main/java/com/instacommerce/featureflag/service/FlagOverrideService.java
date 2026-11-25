package com.instacommerce.featureflag.service;

import com.instacommerce.featureflag.config.FlagCacheEventListener;
import com.instacommerce.featureflag.domain.model.FeatureFlag;
import com.instacommerce.featureflag.domain.model.FlagAuditLog;
import com.instacommerce.featureflag.domain.model.FlagOverride;
import com.instacommerce.featureflag.dto.request.AddOverrideRequest;
import com.instacommerce.featureflag.exception.ApiException;
import com.instacommerce.featureflag.repository.FlagAuditLogRepository;
import com.instacommerce.featureflag.repository.FeatureFlagRepository;
import com.instacommerce.featureflag.repository.FlagOverrideRepository;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class FlagOverrideService {

    private final FeatureFlagRepository flagRepository;
    private final FlagOverrideRepository overrideRepository;
    private final FlagAuditLogRepository auditLogRepository;
    private final FlagCacheEventListener cacheEventListener;

    @Autowired
    public FlagOverrideService(FeatureFlagRepository flagRepository,
                               FlagOverrideRepository overrideRepository,
                               FlagAuditLogRepository auditLogRepository,
                               ObjectProvider<FlagCacheEventListener> cacheEventListenerProvider) {
        this(flagRepository, overrideRepository, auditLogRepository,
                cacheEventListenerProvider != null ? cacheEventListenerProvider.getIfAvailable() : null);
    }

    public FlagOverrideService(FeatureFlagRepository flagRepository,
                               FlagOverrideRepository overrideRepository,
                               FlagAuditLogRepository auditLogRepository,
                               @Nullable FlagCacheEventListener cacheEventListener) {
        this.flagRepository = flagRepository;
        this.overrideRepository = overrideRepository;
        this.auditLogRepository = auditLogRepository;
        this.cacheEventListener = cacheEventListener;
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "flags", key = "#flagKey"),
        @CacheEvict(value = "flags-all", allEntries = true),
        @CacheEvict(value = "flag-overrides", key = "#flagKey + ':' + #request.userId()"),
        @CacheEvict(value = "flag-overrides-bulk", allEntries = true)
    })
    public FlagOverride addOverride(String flagKey, AddOverrideRequest request, String changedBy) {
        if (request == null || request.userId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_OVERRIDE", "userId is required");
        }
        if (request.value() == null || request.value().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_OVERRIDE_VALUE",
                    "Override value must not be blank");
        }
        if (request.expiresAt() != null && !request.expiresAt().isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_OVERRIDE_EXPIRY",
                    "Override expiresAt must be in the future");
        }
        FeatureFlag flag = flagRepository.findByKey(flagKey)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "FLAG_NOT_FOUND",
                        "Flag with key '" + flagKey + "' not found"));

        // Upsert in place so Hibernate never triggers an INSERT before a queued DELETE on the unique constraint.
        Optional<FlagOverride> existing = overrideRepository
                .findByFlagIdAndUserId(flag.getId(), request.userId());
        String oldValue = existing.map(FlagOverride::getOverrideValue).orElse(null);

        FlagOverride override = existing.orElseGet(FlagOverride::new);
        override.setFlagId(flag.getId());
        override.setUserId(request.userId());
        override.setOverrideValue(request.value());
        override.setReason(request.reason());
        override.setCreatedBy(changedBy);
        override.setExpiresAt(request.expiresAt());

        override = overrideRepository.save(override);

        auditLogRepository.save(new FlagAuditLog(flag.getId(), "OVERRIDE_ADDED",
                oldValue,
                request.value() + " (user=" + request.userId() + ")",
                changedBy));

        publishAfterCommit(() -> {
            if (cacheEventListener != null) {
                cacheEventListener.publishBulkUpdate("override_added");
            }
        });
        return override;
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "flags", key = "#flagKey"),
        @CacheEvict(value = "flags-all", allEntries = true),
        @CacheEvict(value = "flag-overrides", key = "#flagKey + ':' + #userId"),
        @CacheEvict(value = "flag-overrides-bulk", allEntries = true)
    })
    public void removeOverride(String flagKey, UUID userId, String changedBy) {
        FeatureFlag flag = flagRepository.findByKey(flagKey)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "FLAG_NOT_FOUND",
                        "Flag with key '" + flagKey + "' not found"));

        FlagOverride override = overrideRepository.findByFlagIdAndUserId(flag.getId(), userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "OVERRIDE_NOT_FOUND",
                        "Override not found for user " + userId));

        overrideRepository.delete(override);

        auditLogRepository.save(new FlagAuditLog(flag.getId(), "OVERRIDE_REMOVED",
                override.getOverrideValue() + " (user=" + userId + ")",
                null,
                changedBy));

        publishAfterCommit(() -> {
            if (cacheEventListener != null) {
                cacheEventListener.publishBulkUpdate("override_removed");
            }
        });
    }

    private static void publishAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    @Cacheable(value = "flag-overrides", key = "#flagKey + ':' + #userId")
    public Optional<FlagOverride> findActiveOverride(String flagKey, UUID flagId, UUID userId) {
        return overrideRepository.findActiveByFlagIdAndUserId(flagId, userId, Instant.now());
    }

    @Cacheable(value = "flag-overrides-bulk", key = "#root.target.bulkCacheKey(#flagIds, #userId)")
    public Map<UUID, FlagOverride> findActiveOverridesByFlagIds(List<UUID> flagIds, UUID userId) {
        if (flagIds == null || flagIds.isEmpty()) {
            return Map.of();
        }
        return overrideRepository.findActiveByFlagIdsAndUserId(flagIds, userId, Instant.now())
                .stream()
                .collect(Collectors.toMap(FlagOverride::getFlagId, Function.identity()));
    }

    String bulkCacheKey(List<UUID> flagIds, UUID userId) {
        if (flagIds == null || flagIds.isEmpty()) {
            return ":" + userId;
        }
        return flagIds.stream()
                .map(UUID::toString)
                .sorted()
                .collect(Collectors.joining(",")) + ":" + userId;
    }
}
