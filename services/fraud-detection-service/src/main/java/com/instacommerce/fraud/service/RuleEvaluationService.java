package com.instacommerce.fraud.service;

import com.instacommerce.fraud.domain.model.FraudRule;
import com.instacommerce.fraud.dto.request.FraudCheckRequest;
import com.instacommerce.platform.geo.GeoUtils;
import java.util.Locale;
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
            case "COMBINATION" -> evaluateCombination(rule, request);
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

        double distance = GeoUtils.haversineKm(expectedLat, expectedLng,
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
                long burstFailedCount = velocityService.getCount("USER", request.userId().toString(),
                        "FAILED_PAYMENTS_5M");
                int maxFailures = ((Number) condition.getOrDefault("maxFailures", 3)).intValue();
                yield Math.max(failedCount, burstFailedCount) >= maxFailures;
            }
            case "VELOCITY_BURST" -> {
                String entityType = (String) condition.getOrDefault("entityType", "USER");
                String counterType = (String) condition.getOrDefault("counterType", "ORDERS_5M");
                int threshold = ((Number) condition.getOrDefault("threshold", 4)).intValue();
                String entityId = resolveEntityId(entityType, request);
                yield entityId != null && velocityService.getCount(entityType, entityId, counterType) >= threshold;
            }
            case "HIGH_RISK_PAYMENT_NEW_DEVICE" -> evaluateCombination(rule, request);
            default -> false;
        };
    }

    private boolean evaluateCombination(FraudRule rule, FraudCheckRequest request) {
        Map<String, Object> condition = rule.getConditionJson() != null ? rule.getConditionJson() : Map.of();
        if (!isHighRiskPaymentMethod(request.paymentMethodType(), condition)) {
            return false;
        }
        long minAmountCents = condition.get("minAmountCents") instanceof Number n
            ? n.longValue()
            : 0L;
        if (request.totalCents() < minAmountCents) {
            return false;
        }
        boolean newUsersOnly = Boolean.TRUE.equals(condition.get("newUsersOnly"));
        if (newUsersOnly && !request.isNewUser()) {
            return false;
        }
        boolean requireNewDevice = condition.get("requireNewDevice") == null
            || Boolean.TRUE.equals(condition.get("requireNewDevice"));
        if (requireNewDevice && !isNewOrUnrecognizedDevice(request)) {
            return false;
        }
        if (condition.get("burstThreshold") instanceof Number burstNum) {
            String userId = request.userId() != null ? request.userId().toString() : null;
            long burstOrders = userId != null ? velocityService.getCount("USER", userId, "ORDERS_5M") : 0L;
            if (burstOrders < burstNum.intValue()) {
                return false;
            }
        }
        return true;
    }

    public boolean isNewOrUnrecognizedDevice(FraudCheckRequest request) {
        if (request.deviceFingerprint() == null || request.deviceFingerprint().isBlank()) {
            return true;
        }
        if (request.isNewUser()) {
            return true;
        }
        return velocityService.getCount("DEVICE", request.deviceFingerprint(), "ORDERS_24H") == 0L;
    }

    public static boolean isHighRiskPaymentMethod(String paymentMethodType, Map<String, Object> condition) {
        if (paymentMethodType == null || paymentMethodType.isBlank()) {
            return false;
        }
        String normalized = paymentMethodType.trim().toUpperCase(Locale.ROOT);
        Object explicitSingle = condition != null ? condition.get("paymentMethodType") : null;
        if (explicitSingle instanceof String target && !target.isBlank()) {
            return normalized.equals(target.trim().toUpperCase(Locale.ROOT));
        }
        Object explicitList = condition != null ? condition.get("paymentMethods") : null;
        if (explicitList instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null && normalized.equals(item.toString().trim().toUpperCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        }
        return switch (normalized) {
            case "COD", "CRYPTO", "GIFT_CARD", "PREPAID_CARD", "INTERNATIONAL_CARD" -> true;
            default -> false;
        };
    }

    public int evaluateDefaultCombinationScore(FraudCheckRequest request) {
        String method = request.paymentMethodType();
        if (!isHighRiskPaymentMethod(method, Map.of())) {
            return 0;
        }
        boolean missingDevice = request.deviceFingerprint() == null || request.deviceFingerprint().isBlank();
        if (!missingDevice && !request.isNewUser()) {
            return 0;
        }
        String normalized = method.trim().toUpperCase(Locale.ROOT);
        return "COD".equals(normalized)
                ? (request.totalCents() >= 100_000L ? (missingDevice ? 60 : 35) : 0)
                : ((missingDevice || request.totalCents() >= 100_000L) ? 80 : 55);
    }

    private String resolveEntityId(String entityType, FraudCheckRequest request) {
        return switch (entityType) {
            case "USER" -> request.userId() != null ? request.userId().toString() : null;
            case "DEVICE" -> request.deviceFingerprint();
            case "IP" -> request.ipAddress();
            default -> null;
        };
    }
}
