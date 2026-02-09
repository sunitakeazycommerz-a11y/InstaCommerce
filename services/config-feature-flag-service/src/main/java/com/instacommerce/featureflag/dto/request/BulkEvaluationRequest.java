package com.instacommerce.featureflag.dto.request;

import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record BulkEvaluationRequest(
    @Size(max = 100) List<String> keys,
    UUID userId,
    Map<String, Object> context
) {
}
