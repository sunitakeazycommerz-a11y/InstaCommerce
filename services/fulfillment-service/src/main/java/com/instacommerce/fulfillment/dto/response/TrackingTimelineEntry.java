package com.instacommerce.fulfillment.dto.response;

import java.time.Instant;

public record TrackingTimelineEntry(
    String status,
    Instant at
) {
}
