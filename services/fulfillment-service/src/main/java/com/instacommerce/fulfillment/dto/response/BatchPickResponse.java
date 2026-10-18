package com.instacommerce.fulfillment.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BatchPickResponse(
    UUID batchId,
    String storeId,
    UUID pickerId,
    String status,
    String cartId,
    int totalOrders,
    int totalItems,
    Instant startedAt,
    Instant completedAt,
    List<BatchToteAssignment> totes,
    List<BatchPickRouteStep> route
) {
}
