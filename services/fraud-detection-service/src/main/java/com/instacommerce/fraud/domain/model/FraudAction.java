package com.instacommerce.fraud.domain.model;

public enum FraudAction {
    ALLOW,
    FLAG,
    REVIEW,
    BLOCK;

    public static FraudAction fromRiskLevel(RiskLevel riskLevel) {
        return switch (riskLevel) {
            case LOW -> ALLOW;
            case MEDIUM -> FLAG;
            case HIGH -> REVIEW;
            case CRITICAL -> BLOCK;
        };
    }

    /**
     * Returns the more severe action between two actions.
     */
    public static FraudAction escalate(FraudAction current, FraudAction candidate) {
        if (candidate.ordinal() > current.ordinal()) {
            return candidate;
        }
        return current;
    }
}
