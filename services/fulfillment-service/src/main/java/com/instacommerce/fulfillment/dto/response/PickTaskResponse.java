package com.instacommerce.fulfillment.dto.response;

import java.time.Instant;
import java.util.UUID;

public record PickTaskResponse(
    UUID id,
    UUID orderId,
    UUID storeId,
    String status,
    UUID pickerId,
    Instant startedAt,
    Instant completedAt,
    Instant createdAt
) {
}
