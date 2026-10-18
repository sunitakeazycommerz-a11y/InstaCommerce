package com.instacommerce.warehouse.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record StagingTelemetryRequest(
    @NotBlank String sensorId,
    @NotBlank String bayId,
    @NotBlank String storageType,
    @NotNull BigDecimal temperatureCelsius,
    Integer batteryPct
) {
}
