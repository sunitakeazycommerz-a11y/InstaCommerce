package com.instacommerce.featureflag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.featureflag.exception.ApiException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

public final class FlagTargetingValidator {

    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]{1,100}$");
    private static final Pattern SEGMENT_PATTERN = Pattern.compile("^[a-zA-Z0-9._:-]{1,64}$");
    private static final int MAX_TARGET_USERS = 10_000;
    private static final int MAX_TARGET_SEGMENTS = 100;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FlagTargetingValidator() {
    }

    public static void validateKey(String key) {
        if (key == null || key.isBlank() || !KEY_PATTERN.matcher(key.trim()).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FLAG_KEY",
                "Flag key must be 1..100 characters matching [a-zA-Z0-9._-]");
        }
    }

    public static void validateRolloutPercentage(Integer percentage) {
        if (percentage == null) {
            return;
        }
        if (percentage < 0 || percentage > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PERCENTAGE",
                "Rollout percentage must be between 0 and 100");
        }
    }

    public static void validateTargetUsersJson(String targetUsersJson) {
        if (targetUsersJson == null || targetUsersJson.isBlank()) {
            return;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(targetUsersJson);
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TARGET_USERS",
                "targetUsers must be valid JSON array");
        }
        if (!root.isArray()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TARGET_USERS",
                "targetUsers must be a JSON array of user IDs");
        }
        if (root.size() > MAX_TARGET_USERS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TARGET_USERS",
                "targetUsers cannot exceed " + MAX_TARGET_USERS + " entries");
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode item : root) {
            if (!item.isTextual()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TARGET_USERS",
                    "targetUsers entries must be non-blank strings");
            }
            String userId = item.asText().trim();
            if (userId.isEmpty() || userId.length() > 128) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TARGET_USERS",
                    "targetUsers entry must be 1..128 characters");
            }
            if (!seen.add(userId)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TARGET_USERS",
                    "targetUsers contains duplicate userId: " + userId);
            }
        }
    }

    public static void validateMetadataAndSegmentsJson(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(metadataJson);
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_METADATA",
                "metadata must be valid JSON object");
        }
        if (!root.isObject()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_METADATA",
                "metadata must be a JSON object");
        }
        validateSegmentArray(root.get("targetSegments"));
        validateSegmentArray(root.get("segments"));

        JsonNode matchMode = root.get("segmentMatchMode");
        if (matchMode != null && !matchMode.isNull()) {
            String mode = matchMode.asText("");
            if (!"ANY".equalsIgnoreCase(mode) && !"ALL".equalsIgnoreCase(mode)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TARGET_SEGMENTS",
                    "segmentMatchMode must be ANY or ALL");
            }
        }

        JsonNode segmentRollouts = root.get("segmentRolloutPercentages");
        if (segmentRollouts != null && !segmentRollouts.isNull()) {
            if (!segmentRollouts.isObject()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TARGET_SEGMENTS",
                    "segmentRolloutPercentages must be a JSON object");
            }
            Iterator<Map.Entry<String, JsonNode>> fields = segmentRollouts.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                validateSegmentName(entry.getKey());
                JsonNode val = entry.getValue();
                if (val == null || !val.isIntegralNumber()) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PERCENTAGE",
                        "Segment rollout percentage for '" + entry.getKey() + "' must be an integer between 0 and 100");
                }
                validateRolloutPercentage(val.intValue());
            }
        }
    }

    private static void validateSegmentArray(JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        if (!node.isArray() || node.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TARGET_SEGMENTS",
                "targetSegments must be a non-empty JSON array of segment names");
        }
        if (node.size() > MAX_TARGET_SEGMENTS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TARGET_SEGMENTS",
                "targetSegments cannot exceed " + MAX_TARGET_SEGMENTS + " entries");
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TARGET_SEGMENTS",
                    "targetSegments entries must be strings");
            }
            String seg = item.asText().trim();
            validateSegmentName(seg);
            if (!seen.add(seg)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TARGET_SEGMENTS",
                    "Duplicate target segment: " + seg);
            }
        }
    }

    private static void validateSegmentName(String segment) {
        if (segment == null || segment.isBlank() || !SEGMENT_PATTERN.matcher(segment.trim()).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TARGET_SEGMENTS",
                "Invalid target segment identifier: " + segment);
        }
    }
}
