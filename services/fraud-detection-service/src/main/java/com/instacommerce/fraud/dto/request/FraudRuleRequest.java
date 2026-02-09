package com.instacommerce.fraud.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FraudRuleRequest(
        @NotBlank String name,
        @NotBlank String ruleType,
        @NotNull Map<String, Object> conditionJson,
        int scoreImpact,
        String action,
        boolean active,
        int priority
) {
}
