package com.instacommerce.inventory.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record StockCheckRequest(
    @NotBlank String storeId,
    @Valid @NotEmpty List<InventoryItemRequest> items
) {
}
