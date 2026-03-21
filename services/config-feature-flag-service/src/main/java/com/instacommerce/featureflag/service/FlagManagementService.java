package com.instacommerce.featureflag.service;

import com.instacommerce.featureflag.config.FlagCacheEventListener;
import com.instacommerce.featureflag.controller.FlagChangeStreamController;
import com.instacommerce.featureflag.domain.model.FeatureFlag;
import com.instacommerce.featureflag.domain.model.FlagAuditLog;
import com.instacommerce.featureflag.dto.request.CreateFlagRequest;
import com.instacommerce.featureflag.dto.request.UpdateFlagRequest;
import com.instacommerce.featureflag.dto.response.FlagResponse;
import com.instacommerce.featureflag.exception.ApiException;
import com.instacommerce.featureflag.repository.FlagAuditLogRepository;
import com.instacommerce.featureflag.repository.FeatureFlagRepository;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FlagManagementService {

    private static final Logger log = LoggerFactory.getLogger(FlagManagementService.class);

    private final FeatureFlagRepository flagRepository;
    private final FlagAuditLogRepository auditLogRepository;
    private final FlagChangeStreamController flagChangeStreamController;
    private final CacheManager cacheManager;
    private final FlagCacheEventListener cacheEventListener;

    public FlagManagementService(FeatureFlagRepository flagRepository,
                                 FlagAuditLogRepository auditLogRepository,
                                 FlagChangeStreamController flagChangeStreamController,
                                 CacheManager cacheManager,
                                 FlagCacheEventListener cacheEventListener) {
        this.flagRepository = flagRepository;
        this.auditLogRepository = auditLogRepository;
        this.flagChangeStreamController = flagChangeStreamController;
        this.cacheManager = cacheManager;
        this.cacheEventListener = cacheEventListener;
    }

    @Transactional
    public FlagResponse createFlag(CreateFlagRequest request, String changedBy) {
        if (flagRepository.findByKey(request.key()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "FLAG_ALREADY_EXISTS",
                    "Flag with key '" + request.key() + "' already exists");
        }

        FeatureFlag flag = new FeatureFlag();
        flag.setKey(request.key());
        flag.setName(request.name());
        flag.setDescription(request.description());
        if (request.flagType() != null) {
            flag.setFlagType(request.flagType());
        }
        if (request.enabled() != null) {
            flag.setEnabled(request.enabled());
        }
        flag.setDefaultValue(request.defaultValue());
        if (request.rolloutPercentage() != null) {
            flag.setRolloutPercentage(request.rolloutPercentage());
        }
        flag.setTargetUsers(request.targetUsers());
        flag.setMetadata(request.metadata());
        flag.setCreatedBy(changedBy);

        flag = flagRepository.save(flag);

        auditLogRepository.save(new FlagAuditLog(flag.getId(), "CREATED", null,
                flagSummary(flag), changedBy));

        return toResponse(flag);
    }

    @Transactional
    @CacheEvict(value = "flags", key = "#key")
    public FlagResponse updateFlag(String key, UpdateFlagRequest request, String changedBy) {
        FeatureFlag flag = findByKeyOrThrow(key);
        String oldValue = flagSummary(flag);

        if (request.name() != null) {
            flag.setName(request.name());
        }
        if (request.description() != null) {
            flag.setDescription(request.description());
        }
        if (request.flagType() != null) {
            flag.setFlagType(request.flagType());
        }
        if (request.enabled() != null) {
            flag.setEnabled(request.enabled());
        }
        if (request.defaultValue() != null) {
            flag.setDefaultValue(request.defaultValue());
        }
        if (request.rolloutPercentage() != null) {
            flag.setRolloutPercentage(request.rolloutPercentage());
        }
        if (request.targetUsers() != null) {
            flag.setTargetUsers(request.targetUsers());
        }
        if (request.metadata() != null) {
            flag.setMetadata(request.metadata());
        }

        flag = flagRepository.save(flag);

        auditLogRepository.save(new FlagAuditLog(flag.getId(), "UPDATED", oldValue,
                flagSummary(flag), changedBy));

        cacheEventListener.publishFlagUpdate(flag.getId(), key, flagSummary(flag));
        flagChangeStreamController.broadcast(key, flag.isEnabled());
        return toResponse(flag);
    }

    @Transactional
    @CacheEvict(value = "flags", key = "#key")
    public FlagResponse enableFlag(String key, String changedBy) {
        FeatureFlag flag = findByKeyOrThrow(key);
        boolean oldEnabled = flag.isEnabled();
        flag.setEnabled(true);
        flag = flagRepository.save(flag);

        auditLogRepository.save(new FlagAuditLog(flag.getId(), "ENABLED",
                String.valueOf(oldEnabled), "true", changedBy));

        cacheEventListener.publishFlagUpdate(flag.getId(), key, "enabled=true");
        flagChangeStreamController.broadcast(key, true);
        return toResponse(flag);
    }

    @Transactional
    @CacheEvict(value = "flags", key = "#key")
    public FlagResponse disableFlag(String key, String changedBy) {
        FeatureFlag flag = findByKeyOrThrow(key);
        boolean oldEnabled = flag.isEnabled();
        flag.setEnabled(false);
        flag = flagRepository.save(flag);

        auditLogRepository.save(new FlagAuditLog(flag.getId(), "DISABLED",
                String.valueOf(oldEnabled), "false", changedBy));

        cacheEventListener.publishFlagUpdate(flag.getId(), key, "enabled=false");
        flagChangeStreamController.broadcast(key, false);
        return toResponse(flag);
    }

    @Transactional
    @CacheEvict(value = "flags", key = "#key")
    public FlagResponse setRolloutPercentage(String key, int percentage, String changedBy) {
        if (percentage < 0 || percentage > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PERCENTAGE",
                    "Rollout percentage must be between 0 and 100");
        }

        FeatureFlag flag = findByKeyOrThrow(key);
        int oldPercentage = flag.getRolloutPercentage();
        flag.setRolloutPercentage(percentage);
        flag = flagRepository.save(flag);

        auditLogRepository.save(new FlagAuditLog(flag.getId(), "UPDATED",
                String.valueOf(oldPercentage), String.valueOf(percentage), changedBy));

        cacheEventListener.publishFlagUpdate(flag.getId(), key, "rollout=" + percentage);
        flagChangeStreamController.broadcast(key, percentage);
        return toResponse(flag);
    }

    @Transactional
    public void forceDisable(String flagKey) {
        FeatureFlag flag = findByKeyOrThrow(flagKey);
        boolean oldEnabled = flag.isEnabled();
        flag.setEnabled(false);
        flagRepository.save(flag);

        // Bypass cache: directly evict to ensure immediate propagation
        if (cacheManager.getCache("flags") != null) {
            cacheManager.getCache("flags").evict(flagKey);
        }

        auditLogRepository.save(new FlagAuditLog(flag.getId(), "EMERGENCY_STOP",
                String.valueOf(oldEnabled), "false", "system"));

        cacheEventListener.publishBulkUpdate("emergency_stop");
        log.warn("flags.force_disable flag={} previous_state={}", flagKey, oldEnabled);
    }

    public FlagResponse getFlag(String key) {
        return toResponse(findByKeyOrThrow(key));
    }

    public List<FlagResponse> getAllFlags() {
        return flagRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public List<FlagAuditLog> getAuditLog(String key) {
        FeatureFlag flag = findByKeyOrThrow(key);
        return auditLogRepository.findByFlagIdOrderByChangedAtDesc(flag.getId());
    }

    private FeatureFlag findByKeyOrThrow(String key) {
        return flagRepository.findByKey(key)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "FLAG_NOT_FOUND",
                        "Flag with key '" + key + "' not found"));
    }

    private String flagSummary(FeatureFlag flag) {
        return "enabled=" + flag.isEnabled() + ",type=" + flag.getFlagType()
                + ",rollout=" + flag.getRolloutPercentage();
    }

    private FlagResponse toResponse(FeatureFlag flag) {
        return new FlagResponse(
                flag.getId(),
                flag.getKey(),
                flag.getName(),
                flag.getDescription(),
                flag.getFlagType(),
                flag.isEnabled(),
                flag.getDefaultValue(),
                flag.getRolloutPercentage(),
                flag.getTargetUsers(),
                flag.getMetadata(),
                flag.getCreatedBy(),
                flag.getCreatedAt(),
                flag.getUpdatedAt(),
                flag.getVersion()
        );
    }
}
