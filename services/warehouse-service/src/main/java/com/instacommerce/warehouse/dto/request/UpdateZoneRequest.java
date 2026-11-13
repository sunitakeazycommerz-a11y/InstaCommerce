package com.instacommerce.warehouse.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateZoneRequest(
    @Size(max = 255) String zoneName,
    @Size(min = 5, max = 10) String pincode,
    @DecimalMin("0.1") @DecimalMax("100.0") BigDecimal deliveryRadiusKm
) {
}
