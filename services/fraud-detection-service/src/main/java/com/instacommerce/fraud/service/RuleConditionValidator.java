package com.instacommerce.fraud.service;

import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class RuleConditionValidator {

    public void validate(String ruleType, Map<String, Object> conditionJson) {
        if (conditionJson == null) {
            throw new IllegalArgumentException("conditionJson is required");
        }
        switch (ruleType) {
            case "VELOCITY" -> validateVelocity(conditionJson);
            case "AMOUNT" -> validateAmount(conditionJson);
            case "DEVICE" -> validateDevice(conditionJson);
            case "GEO" -> validateGeo(conditionJson);
            case "PATTERN" -> validatePattern(conditionJson);
            default -> throw new IllegalArgumentException("Unsupported ruleType: " + ruleType);
        }
    }

    private void validateVelocity(Map<String, Object> conditionJson) {
        validateOptionalString(conditionJson, "entityType");
        validateOptionalString(conditionJson, "counterType");
        validateOptionalNumber(conditionJson, "threshold");
    }

    private void validateAmount(Map<String, Object> conditionJson) {
        validateOptionalNumber(conditionJson, "maxAmountCents");
        validateOptionalBoolean(conditionJson, "newUsersOnly");
    }

    private void validateDevice(Map<String, Object> conditionJson) {
        validateOptionalNumber(conditionJson, "maxAccountsPerDevice");
    }

    private void validateGeo(Map<String, Object> conditionJson) {
        validateOptionalNumber(conditionJson, "maxDistanceKm");
        validateOptionalNumber(conditionJson, "expectedLat");
        validateOptionalNumber(conditionJson, "expectedLng");
    }

    private void validatePattern(Map<String, Object> conditionJson) {
        validateOptionalString(conditionJson, "pattern");
        validateOptionalNumber(conditionJson, "maxItems");
        validateOptionalNumber(conditionJson, "maxFailures");
    }

    private void validateOptionalNumber(Map<String, Object> conditionJson, String key) {
        Object value = conditionJson.get(key);
        if (value != null && !(value instanceof Number)) {
            throw new IllegalArgumentException("Invalid condition type for " + key);
        }
    }

    private void validateOptionalBoolean(Map<String, Object> conditionJson, String key) {
        Object value = conditionJson.get(key);
        if (value != null && !(value instanceof Boolean)) {
            throw new IllegalArgumentException("Invalid condition type for " + key);
        }
    }

    private void validateOptionalString(Map<String, Object> conditionJson, String key) {
        Object value = conditionJson.get(key);
        if (value != null && !(value instanceof String)) {
            throw new IllegalArgumentException("Invalid condition type for " + key);
        }
    }
}
