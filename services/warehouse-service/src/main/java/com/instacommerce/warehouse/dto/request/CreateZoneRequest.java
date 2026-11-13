package com.instacommerce.warehouse.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateZoneRequest(
    @NotBlank @Size(max = 255) String zoneName,
    @NotBlank @Size(min = 5, max = 10) String pincode,
    @DecimalMin("0.1") @DecimalMax("100.0") BigDecimal deliveryRadiusKm
) {
}
