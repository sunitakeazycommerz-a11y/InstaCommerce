package com.instacommerce.warehouse.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateStoreRequest(
    @NotBlank @Size(max = 255) String name,
    @NotBlank String address,
    @NotBlank @Size(max = 100) String city,
    @NotBlank @Size(max = 100) String state,
    @NotBlank @Size(min = 5, max = 10) String pincode,
    @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal latitude,
    @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal longitude,
    @Positive Integer capacityOrdersPerHour
) {
}
