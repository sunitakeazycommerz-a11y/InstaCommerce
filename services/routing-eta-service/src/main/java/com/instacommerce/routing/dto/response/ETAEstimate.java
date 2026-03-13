package com.instacommerce.routing.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

public record ETAEstimate(
    int etaMinutes,
    int etaLowMinutes,
    int etaHighMinutes,
    BigDecimal distanceKm,
    double breachProbability,
    Instant calculatedAt
) {
}
