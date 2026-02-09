package com.instacommerce.wallet.dto.response;

public record ReferralCodeResponse(
    String code,
    int uses,
    int maxUses,
    long rewardCents
) {
}
