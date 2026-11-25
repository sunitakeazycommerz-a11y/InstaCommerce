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
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class FlagManagementService {

    private static final Logger log = LoggerFactory.getLogger(FlagManagementService.class);

    private final FeatureFlagRepository flagRepository;
    private final FlagAuditLogRepository auditLogRepository;
    private final FlagChangeStreamController flagChangeStreamController;
    private final CacheManager cacheManager;
    private final FlagCacheEventListener cacheEventListener;

    @Autowired
    public FlagManagementService(FeatureFlagRepository flagRepository,
                                 FlagAuditLogRepository auditLogRepository,
                                 FlagChangeStreamController flagChangeStreamController,
                                 CacheManager cacheManager,
                                 ObjectProvider<FlagCacheEventListener> cacheEventListenerProvider) {
        this(flagRepository, auditLogRepository, flagChangeStreamController, cacheManager,
                cacheEventListenerProvider != null ? cacheEventListenerProvider.getIfAvailable() : null);
    }

    public FlagManagementService(FeatureFlagRepository flagRepository,
                                 FlagAuditLogRepository auditLogRepository,
                                 FlagChangeStreamController flagChangeStreamController,
                                 CacheManager cacheManager,
                                 @Nullable FlagCacheEventListener cacheEventListener) {
        this.flagRepository = flagRepository;
        this.auditLogRepository = auditLogRepository;
        this.flagChangeStreamController = flagChangeStreamController;
        this.cacheManager = cacheManager;
        this.cacheEventListener = cacheEventListener;
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "flags", key = "#request.key()"),
        @CacheEvict(value = "flags-all", allEntries = true)
    })
    public FlagResponse createFlag(CreateFlagRequest request, String changedBy) {
        FlagTargetingValidator.validateKey(request.key());
        FlagTargetingValidator.validateRolloutPercentage(request.rolloutPercentage());
        FlagTargetingValidator.validateTargetUsersJson(request.targetUsers());
        FlagTargetingValidator.validateMetadataAndSegmentsJson(request.metadata());
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

        UUID flagId = flag.getId();
        String flagKey = flag.getKey();
        String summary = flagSummary(flag);
        boolean enabled = flag.isEnabled();
        publishAfterCommit(() -> {
            if (cacheEventListener != null) {
                cacheEventListener.publishFlagUpdate(flagId, flagKey, summary);
            }
            flagChangeStreamController.broadcast(flagKey, enabled);
        });
        return toResponse(flag);
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "flags", key = "#key"),
        @CacheEvict(value = "flags-all", allEntries = true)
    })
    public FlagResponse updateFlag(String key, UpdateFlagRequest request, String changedBy) {
        FlagTargetingValidator.validateRolloutPercentage(request.rolloutPercentage());
        FlagTargetingValidator.validateTargetUsersJson(request.targetUsers());
        FlagTargetingValidator.validateMetadataAndSegmentsJson(request.metadata());
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

        UUID flagId = flag.getId();
        String summary = flagSummary(flag);
        boolean enabled = flag.isEnabled();
        publishAfterCommit(() -> {
            if (cacheEventListener != null) {
                cacheEventListener.publishFlagUpdate(flagId, key, summary);
            }
            flagChangeStreamController.broadcast(key, enabled);
        });
        return toResponse(flag);
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "flags", key = "#key"),
        @CacheEvict(value = "flags-all", allEntries = true)
    })
    public FlagResponse enableFlag(String key, String changedBy) {
        FeatureFlag flag = findByKeyOrThrow(key);
        boolean oldEnabled = flag.isEnabled();
        flag.setEnabled(true);
        flag = flagRepository.save(flag);

        auditLogRepository.save(new FlagAuditLog(flag.getId(), "ENABLED",
                String.valueOf(oldEnabled), "true", changedBy));

        UUID flagId = flag.getId();
        publishAfterCommit(() -> {
            if (cacheEventListener != null) {
                cacheEventListener.publishFlagUpdate(flagId, key, "enabled=true");
            }
            flagChangeStreamController.broadcast(key, true);
        });
        return toResponse(flag);
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "flags", key = "#key"),
        @CacheEvict(value = "flags-all", allEntries = true)
    })
    public FlagResponse disableFlag(String key, String changedBy) {
        FeatureFlag flag = findByKeyOrThrow(key);
        boolean oldEnabled = flag.isEnabled();
        flag.setEnabled(false);
        flag = flagRepository.save(flag);

        auditLogRepository.save(new FlagAuditLog(flag.getId(), "DISABLED",
                String.valueOf(oldEnabled), "false", changedBy));

        UUID flagId = flag.getId();
        publishAfterCommit(() -> {
            if (cacheEventListener != null) {
                cacheEventListener.publishFlagUpdate(flagId, key, "enabled=false");
            }
            flagChangeStreamController.broadcast(key, false);
        });
        return toResponse(flag);
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "flags", key = "#key"),
        @CacheEvict(value = "flags-all", allEntries = true)
    })
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

        UUID flagId = flag.getId();
        publishAfterCommit(() -> {
            if (cacheEventListener != null) {
                cacheEventListener.publishFlagUpdate(flagId, key, "rollout=" + percentage);
            }
            flagChangeStreamController.broadcast(key, percentage);
        });
        return toResponse(flag);
    }

    @Transactional
    public void forceDisable(String flagKey) {
        forceDisable(flagKey, "system");
    }

    @Transactional
    public void forceDisable(String flagKey, String changedBy) {
        FeatureFlag flag = findByKeyOrThrow(flagKey);
        boolean oldEnabled = flag.isEnabled();
        flag.setEnabled(false);
        flagRepository.save(flag);

        if (cacheManager.getCache("flags") != null) {
            cacheManager.getCache("flags").evict(flagKey);
        }
        if (cacheManager.getCache("flags-all") != null) {
            cacheManager.getCache("flags-all").clear();
        }

        String actor = (changedBy != null && !changedBy.isBlank()) ? changedBy : "system";
        auditLogRepository.save(new FlagAuditLog(flag.getId(), "EMERGENCY_STOP",
                String.valueOf(oldEnabled), "false", actor));

        publishAfterCommit(() -> {
            if (cacheEventListener != null) {
                cacheEventListener.publishBulkUpdate("emergency_stop");
            }
            flagChangeStreamController.broadcast(flagKey, false);
        });
        log.warn("flags.force_disable flag={} previous_state={} by={}", flagKey, oldEnabled, actor);
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
