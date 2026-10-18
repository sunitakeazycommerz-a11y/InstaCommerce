package com.instacommerce.warehouse.dto.response;

import java.time.Instant;
import java.util.UUID;

public record BinResponse(
    UUID id,
    UUID storeId,
    String aisle,
    String rack,
    String shelf,
    String binNumber,
    String sku,
    int maxVolumeCm3,
    int currentVolumeCm3,
    int maxWeightGrams,
    int currentWeightGrams,
    boolean occupied,
    Instant updatedAt
) {
}
