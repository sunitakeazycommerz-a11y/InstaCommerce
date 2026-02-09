package com.instacommerce.order.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
    UUID id,
    UUID userId,
    String storeId,
    String status,
    List<OrderItemResponse> items,
    long subtotalCents,
    long discountCents,
    long totalCents,
    String currency,
    String couponCode,
    Instant createdAt,
    List<OrderStatusTimelineResponse> statusHistory
) {
}
