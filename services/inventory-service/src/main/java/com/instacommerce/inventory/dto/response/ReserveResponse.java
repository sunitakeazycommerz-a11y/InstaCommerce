package com.instacommerce.inventory.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReserveResponse(
    UUID reservationId,
    Instant expiresAt,
    List<ReservedItemResponse> items
) {
}
