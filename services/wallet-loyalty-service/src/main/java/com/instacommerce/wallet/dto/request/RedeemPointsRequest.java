package com.instacommerce.wallet.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RedeemPointsRequest(
    @NotNull @Min(1) Integer points,
    @NotBlank String orderId
) {
}
