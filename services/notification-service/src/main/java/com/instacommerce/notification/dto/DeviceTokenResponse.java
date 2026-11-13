package com.instacommerce.notification.dto;

import java.time.Instant;
import java.util.UUID;

public record DeviceTokenResponse(
    UUID id,
    UUID userId,
    String token,
    String platform,
    boolean active,
    Instant createdAt,
    Instant updatedAt
) {
}
