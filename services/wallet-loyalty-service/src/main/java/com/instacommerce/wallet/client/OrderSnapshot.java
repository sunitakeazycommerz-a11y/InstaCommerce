package com.instacommerce.wallet.client;

import java.time.Instant;
import java.util.UUID;

public record OrderSnapshot(
    UUID orderId,
    UUID userId,
    long totalCents,
    String currency,
    String status,
    Instant createdAt
) {
}
