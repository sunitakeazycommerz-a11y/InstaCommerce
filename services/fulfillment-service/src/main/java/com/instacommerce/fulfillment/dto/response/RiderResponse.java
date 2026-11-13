package com.instacommerce.fulfillment.dto.response;

import java.time.Instant;
import java.util.UUID;

public record RiderResponse(
    UUID id,
    String name,
    String phone,
    UUID storeId,
    boolean available,
    Instant createdAt
) {
}
