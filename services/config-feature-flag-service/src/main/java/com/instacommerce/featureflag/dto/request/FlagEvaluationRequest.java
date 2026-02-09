package com.instacommerce.featureflag.dto.request;

import java.util.Map;
import java.util.UUID;

public record FlagEvaluationRequest(
    String key,
    UUID userId,
    Map<String, Object> context
) {
}
