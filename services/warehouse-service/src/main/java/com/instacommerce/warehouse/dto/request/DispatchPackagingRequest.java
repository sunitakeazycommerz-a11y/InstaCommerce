package com.instacommerce.warehouse.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DispatchPackagingRequest(
    @NotBlank String orderId,
    @NotBlank String storageType,
    @NotNull Boolean hasInsulatedThermalBag,
    @NotNull Boolean hasColdPacks
) {
}
