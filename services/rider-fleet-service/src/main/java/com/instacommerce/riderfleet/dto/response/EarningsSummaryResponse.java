package com.instacommerce.riderfleet.dto.response;

import java.time.Instant;

public record EarningsSummaryResponse(
    long totalEarningsCents,
    long totalDeliveryFeeCents,
    long totalTipCents,
    long totalIncentiveCents,
    int deliveryCount,
    Instant fromDate,
    Instant toDate
) {
}
