package com.instacommerce.order.dto.response;

import java.time.Instant;
import java.util.UUID;

public record OrderSummaryResponse(
    UUID id,
    String status,
    long totalCents,
    String currency,
    Instant createdAt
) {
}
