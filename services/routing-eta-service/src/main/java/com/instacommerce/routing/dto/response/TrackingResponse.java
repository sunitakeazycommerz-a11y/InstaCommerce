package com.instacommerce.routing.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

public record TrackingResponse(
    BigDecimal latitude,
    BigDecimal longitude,
    BigDecimal speedKmh,
    BigDecimal heading,
    Instant recordedAt
) {
}
