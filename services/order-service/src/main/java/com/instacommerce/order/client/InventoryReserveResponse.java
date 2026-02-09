package com.instacommerce.order.client;

import java.time.Instant;
import java.util.UUID;

public record InventoryReserveResponse(
    UUID reservationId,
    Instant expiresAt
) {
}
