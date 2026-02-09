package com.instacommerce.inventory.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record StockAdjustRequest(
    @NotNull UUID productId,
    @NotBlank String storeId,
    @NotNull Integer delta,
    @NotBlank String reason,
    String referenceId
) {
}
