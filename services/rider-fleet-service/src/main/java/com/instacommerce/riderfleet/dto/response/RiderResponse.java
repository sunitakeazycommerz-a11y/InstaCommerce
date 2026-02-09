package com.instacommerce.riderfleet.dto.response;

import com.instacommerce.riderfleet.domain.model.RiderStatus;
import com.instacommerce.riderfleet.domain.model.VehicleType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RiderResponse(
    UUID id,
    String name,
    String phone,
    String email,
    VehicleType vehicleType,
    String licenseNumber,
    RiderStatus status,
    BigDecimal ratingAvg,
    int totalDeliveries,
    UUID storeId,
    Instant createdAt,
    Instant updatedAt
) {
}
