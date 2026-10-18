package com.instacommerce.warehouse.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PickItemLocationRequest(
    @NotBlank String sku,
    @NotBlank String itemName,
    @NotBlank String aisle,
    String shelf,
    String bin,
    String category,
    @Positive Integer quantity
) {
}
