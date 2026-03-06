package com.instacommerce.riderfleet.dto.response;

import java.time.Instant;

public record EarningsSummaryResponse(
    long totalEarningsCents,
    long totalDeliveryFeeCents,
    long totalTipCents,
    long totalIncentiveCents,
    long deliveryCount,
    Instant fromDate,
    Instant toDate
) {
}
