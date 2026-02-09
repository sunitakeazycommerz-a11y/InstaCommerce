package com.instacommerce.order.dto.response;

import java.time.Instant;

public record OrderStatusTimelineResponse(
    String from,
    String to,
    Instant at,
    String note
) {
}
