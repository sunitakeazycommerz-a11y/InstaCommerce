package com.instacommerce.wallet.dto.response;

public record LoyaltyResponse(
    int pointsBalance,
    String tier,
    int lifetimePoints,
    long walletCreditedCents
) {
    public LoyaltyResponse(int pointsBalance, String tier, int lifetimePoints) {
        this(pointsBalance, tier, lifetimePoints, 0L);
    }
}
