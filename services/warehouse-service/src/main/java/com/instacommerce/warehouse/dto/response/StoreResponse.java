package com.instacommerce.warehouse.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record StoreResponse(
    UUID id,
    String name,
    String address,
    String city,
    String state,
    String pincode,
    BigDecimal latitude,
    BigDecimal longitude,
    String status,
    int capacityOrdersPerHour,
    List<ZoneResponse> zones,
    List<HoursResponse> hours,
    Instant createdAt,
    Instant updatedAt
) {
    public record ZoneResponse(
        UUID id,
        String zoneName,
        String pincode,
        BigDecimal deliveryRadiusKm
    ) {
    }

    public record HoursResponse(
        UUID id,
        int dayOfWeek,
        String opensAt,
        String closesAt,
        boolean isHoliday
    ) {
    }
}
