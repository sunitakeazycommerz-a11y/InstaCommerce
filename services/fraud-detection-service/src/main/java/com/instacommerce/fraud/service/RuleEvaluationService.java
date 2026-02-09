package com.instacommerce.fraud.service;

import com.instacommerce.fraud.domain.model.FraudRule;
import com.instacommerce.fraud.dto.request.FraudCheckRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Evaluates individual fraud rules against a check request.
 * Each rule type has specific evaluation logic.
 */
@Service
public class RuleEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(RuleEvaluationService.class);

    private final VelocityService velocityService;

    public RuleEvaluationService(VelocityService velocityService) {
        this.velocityService = velocityService;
    }

    /**
     * Returns true if the rule is triggered for the given request.
     */
    public boolean evaluateRule(FraudRule rule, FraudCheckRequest request) {
        return switch (rule.getRuleType()) {
            case "VELOCITY" -> evaluateVelocity(rule, request);
            case "AMOUNT" -> evaluateAmount(rule, request);
            case "DEVICE" -> evaluateDevice(rule, request);
            case "GEO" -> evaluateGeo(rule, request);
            case "PATTERN" -> evaluatePattern(rule, request);
            default -> {
                log.warn("Unknown rule type: {}", rule.getRuleType());
                yield false;
            }
        };
    }

    private boolean evaluateVelocity(FraudRule rule, FraudCheckRequest request) {
        Map<String, Object> condition = rule.getConditionJson();
        String entityType = (String) condition.getOrDefault("entityType", "USER");
        String counterType = (String) condition.getOrDefault("counterType", "ORDERS_1H");
        int threshold = ((Number) condition.getOrDefault("threshold", 10)).intValue();

        String entityId = resolveEntityId(entityType, request);
        if (entityId == null) {
            return false;
        }

        long count = velocityService.getCount(entityType, entityId, counterType);
        return count >= threshold;
    }

    private boolean evaluateAmount(FraudRule rule, FraudCheckRequest request) {
        Map<String, Object> condition = rule.getConditionJson();
        long threshold = ((Number) condition.getOrDefault("maxAmountCents", 500000)).longValue();
        boolean newUsersOnly = (boolean) condition.getOrDefault("newUsersOnly", false);

        if (newUsersOnly && !request.isNewUser()) {
            return false;
        }

        return request.totalCents() > threshold;
    }

    private boolean evaluateDevice(FraudRule rule, FraudCheckRequest request) {
        if (request.deviceFingerprint() == null || request.deviceFingerprint().isBlank()) {
            return false;
        }

        Map<String, Object> condition = rule.getConditionJson();
        int maxAccounts = ((Number) condition.getOrDefault("maxAccountsPerDevice", 3)).intValue();

        long count = velocityService.getCount("DEVICE", request.deviceFingerprint(), "ORDERS_24H");
        return count >= maxAccounts;
    }

    private boolean evaluateGeo(FraudRule rule, FraudCheckRequest request) {
        if (request.deliveryLat() == null || request.deliveryLng() == null) {
            return false;
        }

        Map<String, Object> condition = rule.getConditionJson();
        double maxDistanceKm = ((Number) condition.getOrDefault("maxDistanceKm", 50.0)).doubleValue();
        double expectedLat = ((Number) condition.getOrDefault("expectedLat", 0.0)).doubleValue();
        double expectedLng = ((Number) condition.getOrDefault("expectedLng", 0.0)).doubleValue();

        // Skip if no expected location configured (no baseline yet)
        if (expectedLat == 0.0 && expectedLng == 0.0) {
            return false;
        }

        double distance = haversineDistanceKm(expectedLat, expectedLng,
                request.deliveryLat(), request.deliveryLng());
        return distance > maxDistanceKm;
    }

    private boolean evaluatePattern(FraudRule rule, FraudCheckRequest request) {
        Map<String, Object> condition = rule.getConditionJson();
        String pattern = (String) condition.getOrDefault("pattern", "");

        return switch (pattern) {
            case "HIGH_VALUE_NEW_USER" ->
                    request.isNewUser() && request.totalCents() > 200000;
            case "HIGH_ITEM_COUNT" ->
                    request.itemCount() > ((Number) condition.getOrDefault("maxItems", 20)).intValue();
            case "RAPID_RETRY" -> {
                long failedCount = velocityService.getCount("USER", request.userId().toString(),
                        "FAILED_PAYMENTS_1H");
                yield failedCount >= ((Number) condition.getOrDefault("maxFailures", 3)).intValue();
            }
            default -> false;
        };
    }

    private String resolveEntityId(String entityType, FraudCheckRequest request) {
        return switch (entityType) {
            case "USER" -> request.userId() != null ? request.userId().toString() : null;
            case "DEVICE" -> request.deviceFingerprint();
            case "IP" -> request.ipAddress();
            default -> null;
        };
    }

    private static double haversineDistanceKm(double lat1, double lng1, double lat2, double lng2) {
        double earthRadiusKm = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadiusKm * c;
    }
}
