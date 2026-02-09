package com.instacommerce.inventory.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record StockAdjustBatchRequest(
    @NotBlank String storeId,
    @NotBlank String reason,
    String referenceId,
    @Valid @NotEmpty List<StockAdjustItemRequest> items
) {
}
