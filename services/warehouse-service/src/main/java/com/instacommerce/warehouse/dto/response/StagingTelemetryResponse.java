package com.instacommerce.warehouse.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record StagingTelemetryResponse(
    UUID id,
    UUID storeId,
    String sensorId,
    String bayId,
    String storageType,
    BigDecimal temperatureCelsius,
    Integer batteryPct,
    Instant recordedAt,
    boolean breached,
    boolean alertTriggered
) {
}
