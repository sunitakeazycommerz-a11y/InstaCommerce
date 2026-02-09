package com.instacommerce.wallet.domain.model;

public enum LoyaltyTier {
    BRONZE(0),
    SILVER(5_000),
    GOLD(25_000),
    PLATINUM(100_000);

    private final int threshold;

    LoyaltyTier(int threshold) {
        this.threshold = threshold;
    }

    public int getThreshold() {
        return threshold;
    }

    public static LoyaltyTier fromLifetimePoints(int lifetimePoints) {
        LoyaltyTier result = BRONZE;
        for (LoyaltyTier tier : values()) {
            if (lifetimePoints >= tier.threshold) {
                result = tier;
            }
        }
        return result;
    }
}
