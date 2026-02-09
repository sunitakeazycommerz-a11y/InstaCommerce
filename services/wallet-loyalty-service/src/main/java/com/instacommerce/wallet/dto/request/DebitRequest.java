package com.instacommerce.wallet.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record DebitRequest(
    @NotNull @Min(1) Long amountCents,
    @NotBlank String referenceType,
    @NotBlank String referenceId
) {
}
