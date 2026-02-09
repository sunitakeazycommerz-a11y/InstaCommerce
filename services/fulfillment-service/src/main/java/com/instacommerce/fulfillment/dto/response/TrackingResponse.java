package com.instacommerce.fulfillment.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TrackingResponse(
    UUID orderId,
    String status,
    String riderName,
    Integer estimatedMinutes,
    Instant dispatchedAt,
    List<TrackingTimelineEntry> timeline
) {
}
