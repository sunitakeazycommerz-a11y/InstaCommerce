package com.instacommerce.warehouse.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BinAssignmentRequest(
    @NotBlank @Size(max = 20) String aisle,
    @NotBlank @Size(max = 20) String rack,
    @NotBlank @Size(max = 20) String shelf,
    @NotBlank @Size(max = 20) String binNumber,
    @NotBlank @Size(max = 100) String sku,
    @Positive Integer volumeCm3,
    @PositiveOrZero Integer weightGrams
) {
}
