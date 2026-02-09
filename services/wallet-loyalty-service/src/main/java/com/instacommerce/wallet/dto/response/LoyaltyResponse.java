package com.instacommerce.wallet.dto.response;

public record LoyaltyResponse(
    int pointsBalance,
    String tier,
    int lifetimePoints
) {
}
