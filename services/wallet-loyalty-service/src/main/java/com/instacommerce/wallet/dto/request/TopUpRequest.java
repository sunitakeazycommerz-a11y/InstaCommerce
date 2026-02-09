package com.instacommerce.wallet.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record TopUpRequest(
    @NotNull @Min(100) Long amountCents,
    @NotBlank(message = "paymentReference is required") String paymentReference
) {
}
