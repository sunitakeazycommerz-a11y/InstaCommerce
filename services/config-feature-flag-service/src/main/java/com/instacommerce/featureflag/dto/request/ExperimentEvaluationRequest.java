package com.instacommerce.featureflag.dto.request;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.UUID;

public record ExperimentEvaluationRequest(
    @NotBlank String key,
    UUID userId,
    String assignmentKey,
    Map<String, Object> context
) {
}
