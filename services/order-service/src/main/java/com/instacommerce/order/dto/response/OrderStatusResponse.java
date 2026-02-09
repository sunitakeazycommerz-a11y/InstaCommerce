package com.instacommerce.order.dto.response;

import java.util.List;
import java.util.UUID;

public record OrderStatusResponse(
    UUID orderId,
    String status,
    List<OrderStatusTimelineResponse> timeline
) {
}
