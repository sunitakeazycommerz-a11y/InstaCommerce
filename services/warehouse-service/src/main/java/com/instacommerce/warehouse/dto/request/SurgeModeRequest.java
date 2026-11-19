package com.instacommerce.warehouse.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SurgeModeRequest(
    @NotNull Boolean enabled,
    @PositiveOrZero Integer etaExtensionMinutes,
    @Min(1) @Max(100) Integer thresholdPercent
) {
}
