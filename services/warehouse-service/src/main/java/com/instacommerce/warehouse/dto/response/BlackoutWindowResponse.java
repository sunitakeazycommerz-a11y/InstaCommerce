package com.instacommerce.warehouse.dto.response;

import java.time.Instant;
import java.util.UUID;

public record BlackoutWindowResponse(
    UUID id,
    UUID storeId,
    Instant startsAt,
    Instant endsAt,
    String reason,
    Instant createdAt
) {
}
