package com.instacommerce.featureflag.dto.response;

import java.util.Map;

public record BulkEvaluationResponse(
    Map<String, FlagEvaluationResponse> evaluations
) {
}
