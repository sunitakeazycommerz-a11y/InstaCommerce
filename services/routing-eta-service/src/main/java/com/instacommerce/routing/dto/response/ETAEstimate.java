package com.instacommerce.routing.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

public record ETAEstimate(
    int etaMinutes,
    int etaLowMinutes,
    int etaHighMinutes,
    BigDecimal distanceKm,
    double breachProbability,
    Instant calculatedAt,
    boolean detourDetected,
    boolean monsoonDelayActive,
    String delayBanner,
    String vehicleRecommendation
) {
    public ETAEstimate(
        int etaMinutes,
        int etaLowMinutes,
        int etaHighMinutes,
        BigDecimal distanceKm,
        double breachProbability,
        Instant calculatedAt
    ) {
        this(etaMinutes, etaLowMinutes, etaHighMinutes, distanceKm, breachProbability, calculatedAt,
             false, false, null, null);
    }
}
