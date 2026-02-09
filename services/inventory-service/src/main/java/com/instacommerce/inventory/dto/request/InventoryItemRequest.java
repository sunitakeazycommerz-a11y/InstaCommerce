package com.instacommerce.inventory.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record InventoryItemRequest(
    @NotNull UUID productId,
    @NotNull @Positive Integer quantity
) {
}
