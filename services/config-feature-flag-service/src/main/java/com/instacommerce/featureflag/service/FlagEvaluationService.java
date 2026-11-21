package com.instacommerce.featureflag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.hash.Hashing;
import com.instacommerce.featureflag.domain.model.FeatureFlag;
import com.instacommerce.featureflag.domain.model.FlagOverride;
import com.instacommerce.featureflag.dto.response.FlagEvaluationResponse;
import com.instacommerce.featureflag.repository.FeatureFlagRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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
    private static final int PARSED_CACHE_MAX_SIZE = 4096;

    private final FeatureFlagRepository flagRepository;
    private final FlagOverrideService overrideService;
    private final ObjectMapper objectMapper;
    private final Cache<String, ParsedSegmentMetadata> segmentMetadataCache = Caffeine.newBuilder()
            .maximumSize(PARSED_CACHE_MAX_SIZE)
            .build();
    private final Cache<String, Set<String>> targetUsersCache = Caffeine.newBuilder()
            .maximumSize(PARSED_CACHE_MAX_SIZE)
            .build();

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

            case PERCENTAGE -> evaluateWithPercentage(key, userId, flag.getRolloutPercentage());

            case USER_LIST -> evaluateUserList(flag, key, userId);

            case JSON -> new FlagEvaluationResponse(key,
                    parseValue(flag.getDefaultValue()),
                    FlagEvaluationResponse.SOURCE_DEFAULT);
        };
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

    private record ParsedSegmentMetadata(
            boolean valid,
            List<String> targetSegments,
            boolean requireAll,
            Map<String, Integer> segmentRollouts) {
        static final ParsedSegmentMetadata INVALID = new ParsedSegmentMetadata(false, List.of(), false, Map.of());
        static final ParsedSegmentMetadata UNRESTRICTED = new ParsedSegmentMetadata(true, List.of(), false, Map.of());
    }

    private SegmentMatchResult evaluateTargetSegments(FeatureFlag flag, Map<String, Object> context) {
        String metadata = flag.getMetadata();
        if (metadata == null || metadata.isBlank()) {
            return new SegmentMatchResult(true, null);
        }
        ParsedSegmentMetadata parsed = segmentMetadataCache.get(metadata, raw -> parseSegmentMetadata(raw, flag.getKey()));
        if (parsed == null || !parsed.valid()) {
            return new SegmentMatchResult(false, null);
        }
        if (parsed.targetSegments().isEmpty()) {
            return new SegmentMatchResult(true, null);
        }
        Set<String> callerSegments = extractCallerSegments(context);
        if (callerSegments.isEmpty()) {
            return new SegmentMatchResult(false, null);
        }
        List<String> matchedSegments = new ArrayList<>();
        for (String target : parsed.targetSegments()) {
            if (callerSegments.contains(target)) {
                matchedSegments.add(target);
            }
        }
        boolean matched = parsed.requireAll()
                ? matchedSegments.size() == parsed.targetSegments().size()
                : !matchedSegments.isEmpty();
        if (!matched) {
            return new SegmentMatchResult(false, null);
        }
        for (String matchedSeg : matchedSegments) {
            Integer overridePct = parsed.segmentRollouts().get(matchedSeg);
            if (overridePct != null) {
                return new SegmentMatchResult(true, overridePct);
            }
        }
        return new SegmentMatchResult(true, null);
    }

    private ParsedSegmentMetadata parseSegmentMetadata(String metadata, String flagKey) {
        try {
            JsonNode root = objectMapper.readTree(metadata);
            if (root == null || !root.isObject()) {
                log.warn("Invalid non-object metadata JSON for flag '{}'", flagKey);
                return ParsedSegmentMetadata.INVALID;
            }
            JsonNode segmentsNode = root.get("targetSegments");
            if (segmentsNode == null || segmentsNode.isNull()) {
                segmentsNode = root.get("segments");
            }
            if (segmentsNode == null || segmentsNode.isNull()) {
                return ParsedSegmentMetadata.UNRESTRICTED;
            }
            if (!segmentsNode.isArray()) {
                log.warn("Invalid non-array targetSegments for flag '{}'", flagKey);
                return ParsedSegmentMetadata.INVALID;
            }
            if (segmentsNode.isEmpty()) {
                return ParsedSegmentMetadata.UNRESTRICTED;
            }
            List<String> targets = new ArrayList<>(segmentsNode.size());
            for (JsonNode item : segmentsNode) {
                if (!item.isTextual()) {
                    log.warn("Invalid non-textual targetSegment entry for flag '{}'", flagKey);
                    return ParsedSegmentMetadata.INVALID;
                }
                String normalized = normalizeSegment(item.asText());
                if (!normalized.isEmpty()) {
                    targets.add(normalized);
                }
            }
            if (targets.isEmpty()) {
                return ParsedSegmentMetadata.INVALID;
            }
            boolean requireAll = "ALL".equalsIgnoreCase(root.path("segmentMatchMode").asText("ANY"));
            Map<String, Integer> rollouts = new HashMap<>();
            JsonNode rolloutsNode = root.get("segmentRolloutPercentages");
            if (rolloutsNode != null && !rolloutsNode.isNull()) {
                if (!rolloutsNode.isObject()) {
                    log.warn("Invalid non-object segmentRolloutPercentages for flag '{}'", flagKey);
                    return ParsedSegmentMetadata.INVALID;
                }
                Iterator<Map.Entry<String, JsonNode>> fields = rolloutsNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    String normalizedKey = normalizeSegment(entry.getKey());
                    JsonNode pctNode = entry.getValue();
                    if (!normalizedKey.isEmpty() && pctNode != null && pctNode.isIntegralNumber()) {
                        rollouts.put(normalizedKey, pctNode.intValue());
                    }
                }
            }
            return new ParsedSegmentMetadata(true, List.copyOf(targets), requireAll, Map.copyOf(rollouts));
        } catch (Exception ex) {
            log.warn("Failed to parse target segments metadata for flag '{}': {}", flagKey, ex.getMessage());
            return ParsedSegmentMetadata.INVALID;
        }
    }

    private static String normalizeSegment(String segment) {
        return segment == null ? "" : segment.trim().toLowerCase(Locale.ROOT);
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
                    String normalized = normalizeSegment(item.toString());
                    if (!normalized.isEmpty()) {
                        acc.add(normalized);
                    }
                }
            }
            return;
        }
        for (String token : value.toString().split(",")) {
            String normalized = normalizeSegment(token);
            if (!normalized.isEmpty()) {
                acc.add(normalized);
            }
        }
    }

    private FlagEvaluationResponse evaluateUserList(FeatureFlag flag, String key, UUID userId) {
        if (userId == null || flag.getTargetUsers() == null) {
            return new FlagEvaluationResponse(key, false, FlagEvaluationResponse.SOURCE_DEFAULT);
        }
        Set<String> targetUsers = targetUsersCache.get(flag.getTargetUsers(), raw -> parseTargetUsers(raw, key));
        boolean isTargeted = targetUsers != null && targetUsers.contains(userId.toString());
        return new FlagEvaluationResponse(key, isTargeted,
                isTargeted ? FlagEvaluationResponse.SOURCE_USER_LIST
                        : FlagEvaluationResponse.SOURCE_DEFAULT);
    }

    private Set<String> parseTargetUsers(String rawTargetUsers, String key) {
        try {
            List<String> targetUsers = objectMapper.readValue(rawTargetUsers, STRING_LIST_TYPE);
            return Set.copyOf(targetUsers);
        } catch (Exception e) {
            log.warn("Failed to parse target_users for flag '{}': {}", key, e.getMessage());
            return Set.of();
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
