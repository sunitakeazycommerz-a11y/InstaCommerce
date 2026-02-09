package com.instacommerce.routing.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

public record ETAResponse(
    int estimatedMinutes,
    BigDecimal distanceKm,
    Instant calculatedAt
) {
}
