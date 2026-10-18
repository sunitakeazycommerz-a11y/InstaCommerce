package com.instacommerce.fulfillment.dto.response;

import java.time.Instant;
import java.util.UUID;

public record DeliveryResponse(
    UUID id,
    UUID orderId,
    String status,
    UUID riderId,
    String riderName,
    Integer estimatedMinutes,
    Instant dispatchedAt,
    Instant deliveredAt,
    String dispatchOtp,
    String podPhotoUrl
) {
    public DeliveryResponse(UUID id, UUID orderId, String status, UUID riderId,
                            String riderName, Integer estimatedMinutes,
                            Instant dispatchedAt, Instant deliveredAt) {
        this(id, orderId, status, riderId, riderName, estimatedMinutes, dispatchedAt, deliveredAt, null, null);
    }
}
