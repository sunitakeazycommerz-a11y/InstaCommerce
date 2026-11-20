package com.instacommerce.wallet.dto.request;

import com.instacommerce.wallet.domain.model.WalletTransaction;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreditRequest(
    @NotNull UUID userId,
    @Min(1) long amountCents,
    @NotNull WalletTransaction.ReferenceType referenceType,
    @NotBlank String referenceId,
    String description
) {
}
