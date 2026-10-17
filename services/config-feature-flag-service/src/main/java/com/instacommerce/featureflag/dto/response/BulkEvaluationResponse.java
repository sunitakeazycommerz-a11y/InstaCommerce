package com.instacommerce.featureflag.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record BulkEvaluationResponse(
    @JsonProperty("evaluations") Map<String, FlagEvaluationResponse> evaluations
) {
    @JsonProperty("results")
    public Map<String, FlagEvaluationResponse> results() {
        return evaluations;
    }
}
