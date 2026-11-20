package com.instacommerce.featureflag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.hash.Hashing;
import com.instacommerce.featureflag.domain.model.FeatureFlag;
import com.instacommerce.featureflag.domain.model.FlagOverride;
import com.instacommerce.featureflag.dto.response.FlagEvaluationResponse;
import com.instacommerce.featureflag.repository.FeatureFlagRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
        if (!flag.isEnabled()) {
            return new FlagEvaluationResponse(key, false, FlagEvaluationResponse.SOURCE_DEFAULT);
        }

        SegmentMatchResult segmentMatch = evaluateTargetSegments(flag, context);
        if (!segmentMatch.allowed()) {
            return new FlagEvaluationResponse(key, false, FlagEvaluationResponse.SOURCE_DEFAULT);
        }
        if (segmentMatch.rolloutPercentageOverride() != null) {
            return evaluateWithPercentage(key, userId, segmentMatch.rolloutPercentageOverride());
        }

        return switch (flag.getFlagType()) {
            case BOOLEAN -> new FlagEvaluationResponse(key, true,
                    FlagEvaluationResponse.SOURCE_DEFAULT);

            case PERCENTAGE -> evaluatePercentage(flag, key, userId);

            case USER_LIST -> evaluateUserList(flag, key, userId);

            case JSON -> new FlagEvaluationResponse(key,
                    parseValue(flag.getDefaultValue()),
                    FlagEvaluationResponse.SOURCE_DEFAULT);
        };
    }

    private FlagEvaluationResponse evaluatePercentage(FeatureFlag flag, String key, UUID userId) {
        if (!flag.isEnabled()) {
            return new FlagEvaluationResponse(key, false, FlagEvaluationResponse.SOURCE_DEFAULT);
        }
        return evaluateWithPercentage(key, userId, flag.getRolloutPercentage());
    }

    private FlagEvaluationResponse evaluateWithPercentage(String key, UUID userId, int configuredPercentage) {
        int clampedPercentage = Math.max(0, Math.min(100, configuredPercentage));
        if (clampedPercentage <= 0) {
            return new FlagEvaluationResponse(key, false, FlagEvaluationResponse.SOURCE_PERCENTAGE);
        }
        if (userId == null) {
            return new FlagEvaluationResponse(key, false, FlagEvaluationResponse.SOURCE_DEFAULT);
        }
        if (clampedPercentage >= 100) {
            return new FlagEvaluationResponse(key, true, FlagEvaluationResponse.SOURCE_PERCENTAGE);
        }
        // Consistent hashing: hash(userId + flagKey) % 100 — same user always gets same result
        int bucket = computeBucket(userId, key);
        boolean inRollout = bucket < clampedPercentage;
        return new FlagEvaluationResponse(key, inRollout, FlagEvaluationResponse.SOURCE_PERCENTAGE);
    }

    private record SegmentMatchResult(boolean allowed, Integer rolloutPercentageOverride) {
    }

    private SegmentMatchResult evaluateTargetSegments(FeatureFlag flag, Map<String, Object> context) {
        String metadata = flag.getMetadata();
        if (metadata == null || metadata.isBlank()) {
            return new SegmentMatchResult(true, null);
        }
        try {
            JsonNode root = objectMapper.readTree(metadata);
            if (root == null || !root.isObject()) {
                return new SegmentMatchResult(true, null);
            }
            JsonNode segmentsNode = root.get("targetSegments");
            if (segmentsNode == null || segmentsNode.isNull()) {
                segmentsNode = root.get("segments");
            }
            if (segmentsNode == null || !segmentsNode.isArray() || segmentsNode.isEmpty()) {
                return new SegmentMatchResult(true, null);
            }
            Set<String> callerSegments = extractCallerSegments(context);
            if (callerSegments.isEmpty()) {
                return new SegmentMatchResult(false, null);
            }
            boolean requireAll = "ALL".equalsIgnoreCase(root.path("segmentMatchMode").asText("ANY"));
            List<String> matchedSegments = new ArrayList<>();
            int totalRequired = 0;
            for (JsonNode item : segmentsNode) {
                if (item.isTextual()) {
                    String target = item.asText().trim().toLowerCase(Locale.ROOT);
                    if (!target.isEmpty()) {
                        totalRequired++;
                        if (callerSegments.contains(target)) {
                            matchedSegments.add(item.asText().trim());
                        }
                    }
                }
            }
            boolean matched = requireAll
                ? (totalRequired > 0 && matchedSegments.size() == totalRequired)
                : !matchedSegments.isEmpty();
            if (!matched) {
                return new SegmentMatchResult(false, null);
            }
            JsonNode rolloutsNode = root.get("segmentRolloutPercentages");
            if (rolloutsNode != null && rolloutsNode.isObject()) {
                for (String matchedSeg : matchedSegments) {
                    JsonNode pctNode = rolloutsNode.get(matchedSeg);
                    if (pctNode != null && pctNode.isIntegralNumber()) {
                        return new SegmentMatchResult(true, pctNode.intValue());
                    }
                }
            }
            return new SegmentMatchResult(true, null);
        } catch (Exception ex) {
            log.warn("Failed to evaluate target segments for flag '{}': {}", flag.getKey(), ex.getMessage());
            return new SegmentMatchResult(true, null);
        }
    }

    private Set<String> extractCallerSegments(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return Set.of();
        }
        Set<String> segments = new HashSet<>();
        addContextSegments(segments, context.get("segment"));
        addContextSegments(segments, context.get("segments"));
        addContextSegments(segments, context.get("tier"));
        addContextSegments(segments, context.get("region"));
        addContextSegments(segments, context.get("city"));
        return segments;
    }

    private void addContextSegments(Set<String> acc, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Collection<?> col) {
            for (Object item : col) {
                if (item != null) {
                    String trimmed = item.toString().trim().toLowerCase(Locale.ROOT);
                    if (!trimmed.isEmpty()) {
                        acc.add(trimmed);
                    }
                }
            }
            return;
        }
        for (String token : value.toString().split(",")) {
            String trimmed = token.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                acc.add(trimmed);
            }
        }
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
