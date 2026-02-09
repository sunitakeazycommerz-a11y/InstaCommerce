package com.instacommerce.fraud.domain.model;

public enum RiskLevel {
    LOW(0, 25),
    MEDIUM(26, 50),
    HIGH(51, 75),
    CRITICAL(76, 100);

    private final int minScore;
    private final int maxScore;

    RiskLevel(int minScore, int maxScore) {
        this.minScore = minScore;
        this.maxScore = maxScore;
    }

    public static RiskLevel fromScore(int score) {
        if (score >= CRITICAL.minScore) {
            return CRITICAL;
        }
        if (score >= HIGH.minScore) {
            return HIGH;
        }
        if (score >= MEDIUM.minScore) {
            return MEDIUM;
        }
        return LOW;
    }

    public int getMinScore() {
        return minScore;
    }

    public int getMaxScore() {
        return maxScore;
    }
}
