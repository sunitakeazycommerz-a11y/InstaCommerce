package com.instacommerce.featureflag.dto.request;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record BulkEvaluationRequest(
    List<String> keys,
    UUID userId,
    Map<String, Object> context
) {
}
