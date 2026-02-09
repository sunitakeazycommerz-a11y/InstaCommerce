package com.instacommerce.featureflag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.hash.Hashing;
import com.instacommerce.featureflag.domain.model.FeatureFlag;
import com.instacommerce.featureflag.domain.model.FlagOverride;
import com.instacommerce.featureflag.dto.response.FlagEvaluationResponse;
import com.instacommerce.featureflag.repository.FeatureFlagRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * HOT PATH — evaluated on every request. Must complete in < 10ms.
 * Uses Caffeine cache (30s TTL) to avoid DB hits. Consistent hashing
 * via Murmur3 ensures the same user always gets the same rollout result.
 */
@Service
public class FlagEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(FlagEvaluationService.class);
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    private final FeatureFlagRepository flagRepository;
    private final FlagOverrideService overrideService;
    private final ObjectMapper objectMapper;

    public FlagEvaluationService(FeatureFlagRepository flagRepository,
                                 FlagOverrideService overrideService,
                                 ObjectMapper objectMapper) {
        this.flagRepository = flagRepository;
        this.overrideService = overrideService;
        this.objectMapper = objectMapper;
    }

    public FlagEvaluationResponse evaluate(String key, UUID userId, Map<String, Object> context) {
        FeatureFlag flag = loadFlag(key);
        if (flag == null) {
            return new FlagEvaluationResponse(key, false, FlagEvaluationResponse.SOURCE_DEFAULT);
        }

        FlagOverride override = null;
        if (userId != null) {
            Optional<FlagOverride> overrideCandidate = overrideService
                    .findActiveOverride(flag.getKey(), flag.getId(), userId);
            override = overrideCandidate.orElse(null);
        }

        return evaluate(flag, key, userId, context, override);
    }

    @Cacheable(value = "flags", key = "#key")
    public FeatureFlag loadFlag(String key) {
        return flagRepository.findByKey(key).orElse(null);
    }

    FlagEvaluationResponse evaluate(FeatureFlag flag, String key, UUID userId, Map<String, Object> context,
                                    FlagOverride override) {
        if (flag == null) {
            return new FlagEvaluationResponse(key, false, FlagEvaluationResponse.SOURCE_DEFAULT);
        }
        if (override != null) {
            return new FlagEvaluationResponse(key,
                    parseValue(override.getOverrideValue()),
                    FlagEvaluationResponse.SOURCE_OVERRIDE);
        }

        return switch (flag.getFlagType()) {
            case BOOLEAN -> new FlagEvaluationResponse(key, flag.isEnabled(),
                    FlagEvaluationResponse.SOURCE_DEFAULT);

            case PERCENTAGE -> evaluatePercentage(flag, key, userId);

            case USER_LIST -> evaluateUserList(flag, key, userId);

            case JSON -> new FlagEvaluationResponse(key,
                    parseValue(flag.getDefaultValue()),
                    FlagEvaluationResponse.SOURCE_DEFAULT);
        };
    }

    private FlagEvaluationResponse evaluatePercentage(FeatureFlag flag, String key, UUID userId) {
        if (!flag.isEnabled() || userId == null) {
            return new FlagEvaluationResponse(key, false, FlagEvaluationResponse.SOURCE_DEFAULT);
        }

        // Consistent hashing: hash(userId + flagKey) % 100 — same user always gets same result
        int bucket = computeBucket(userId, key);
        boolean inRollout = bucket < flag.getRolloutPercentage();
        return new FlagEvaluationResponse(key, inRollout, FlagEvaluationResponse.SOURCE_PERCENTAGE);
    }

    private FlagEvaluationResponse evaluateUserList(FeatureFlag flag, String key, UUID userId) {
        if (!flag.isEnabled() || userId == null || flag.getTargetUsers() == null) {
            return new FlagEvaluationResponse(key, false, FlagEvaluationResponse.SOURCE_DEFAULT);
        }

        try {
            List<String> targetUsers = objectMapper.readValue(flag.getTargetUsers(), STRING_LIST_TYPE);
            boolean isTargeted = targetUsers.contains(userId.toString());
            return new FlagEvaluationResponse(key, isTargeted,
                    isTargeted ? FlagEvaluationResponse.SOURCE_USER_LIST
                            : FlagEvaluationResponse.SOURCE_DEFAULT);
        } catch (Exception e) {
            log.warn("Failed to parse target_users for flag '{}': {}", key, e.getMessage());
            return new FlagEvaluationResponse(key, false, FlagEvaluationResponse.SOURCE_DEFAULT);
        }
    }

    @SuppressWarnings("deprecation")
    static int computeBucket(UUID userId, String flagKey) {
        String input = userId.toString() + ":" + flagKey;
        int hash = Hashing.murmur3_32_fixed().hashString(input, StandardCharsets.UTF_8).asInt();
        return (hash & 0x7FFFFFFF) % 100;
    }

    private Object parseValue(String value) {
        if (value == null) {
            return null;
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.parseBoolean(value);
        }
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (Exception e) {
            return value;
        }
    }
}
