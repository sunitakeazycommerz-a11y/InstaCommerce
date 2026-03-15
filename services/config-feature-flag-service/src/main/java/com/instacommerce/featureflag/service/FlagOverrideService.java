package com.instacommerce.featureflag.service;

import com.instacommerce.featureflag.domain.model.FeatureFlag;
import com.instacommerce.featureflag.domain.model.FlagAuditLog;
import com.instacommerce.featureflag.domain.model.FlagOverride;
import com.instacommerce.featureflag.dto.request.AddOverrideRequest;
import com.instacommerce.featureflag.exception.ApiException;
import com.instacommerce.featureflag.repository.FlagAuditLogRepository;
import com.instacommerce.featureflag.repository.FeatureFlagRepository;
import com.instacommerce.featureflag.repository.FlagOverrideRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FlagOverrideService {

    private final FeatureFlagRepository flagRepository;
    private final FlagOverrideRepository overrideRepository;
    private final FlagAuditLogRepository auditLogRepository;

    public FlagOverrideService(FeatureFlagRepository flagRepository,
                               FlagOverrideRepository overrideRepository,
                               FlagAuditLogRepository auditLogRepository) {
        this.flagRepository = flagRepository;
        this.overrideRepository = overrideRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "flags", key = "#flagKey"),
        @CacheEvict(value = "flag-overrides", key = "#flagKey + ':' + #request.userId()"),
        @CacheEvict(value = "flag-overrides-bulk", allEntries = true)
    })
    public FlagOverride addOverride(String flagKey, AddOverrideRequest request, String changedBy) {
        FeatureFlag flag = flagRepository.findByKey(flagKey)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "FLAG_NOT_FOUND",
                        "Flag with key '" + flagKey + "' not found"));

        // Upsert: remove existing override for this user if present
        Optional<FlagOverride> existing = overrideRepository
                .findByFlagIdAndUserId(flag.getId(), request.userId());
        existing.ifPresent(overrideRepository::delete);

        FlagOverride override = new FlagOverride();
        override.setFlagId(flag.getId());
        override.setUserId(request.userId());
        override.setOverrideValue(request.value());
        override.setReason(request.reason());
        override.setCreatedBy(changedBy);
        override.setExpiresAt(request.expiresAt());

        override = overrideRepository.save(override);

        auditLogRepository.save(new FlagAuditLog(flag.getId(), "OVERRIDE_ADDED",
                existing.map(FlagOverride::getOverrideValue).orElse(null),
                request.value() + " (user=" + request.userId() + ")",
                changedBy));

        return override;
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "flags", key = "#flagKey"),
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
    }

    @Cacheable(value = "flag-overrides", key = "#flagKey + ':' + #userId")
    public Optional<FlagOverride> findActiveOverride(String flagKey, UUID flagId, UUID userId) {
        return overrideRepository.findActiveByFlagIdAndUserId(flagId, userId, Instant.now());
    }

    @Cacheable(value = "flag-overrides-bulk", key = "#flagIds.stream().map(T(String)::valueOf).sorted().collect(T(java.util.stream.Collectors).joining(',')) + ':' + #userId")
    public Map<UUID, FlagOverride> findActiveOverridesByFlagIds(List<UUID> flagIds, UUID userId) {
        if (flagIds.isEmpty()) {
            return Map.of();
        }
        return overrideRepository.findActiveByFlagIdsAndUserId(flagIds, userId, Instant.now())
                .stream()
                .collect(Collectors.toMap(FlagOverride::getFlagId, Function.identity()));
    }
}
