package com.instacommerce.wallet.dto.request;

import jakarta.validation.constraints.NotBlank;

public record RedeemReferralRequest(
    @NotBlank String code
) {
}
