package com.instacommerce.inventory.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record StockAdjustBatchRequest(
    @NotNull UUID storeId,
    @NotBlank String reason,
    String referenceId,
    @Valid @NotEmpty List<StockAdjustItemRequest> items
) {
}
