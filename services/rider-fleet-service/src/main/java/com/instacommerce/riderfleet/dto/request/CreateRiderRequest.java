package com.instacommerce.riderfleet.dto.request;

import com.instacommerce.riderfleet.domain.model.VehicleType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateRiderRequest(
    @NotBlank String name,
    @NotBlank String phone,
    @Email String email,
    @NotNull VehicleType vehicleType,
    String licenseNumber,
    UUID storeId
) {
}
