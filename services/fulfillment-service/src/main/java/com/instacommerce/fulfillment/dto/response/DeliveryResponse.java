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
    String podPhotoUrl,
    boolean coldChainRequired,
    boolean fragileRequired,
    Integer orderWeightGrams
) {
    public DeliveryResponse(UUID id, UUID orderId, String status, UUID riderId,
                            String riderName, Integer estimatedMinutes,
                            Instant dispatchedAt, Instant deliveredAt) {
        this(id, orderId, status, riderId, riderName, estimatedMinutes, dispatchedAt, deliveredAt,
             null, null, false, false, null);
    }

    public DeliveryResponse(UUID id, UUID orderId, String status, UUID riderId,
                            String riderName, Integer estimatedMinutes,
                            Instant dispatchedAt, Instant deliveredAt,
                            String dispatchOtp, String podPhotoUrl) {
        this(id, orderId, status, riderId, riderName, estimatedMinutes, dispatchedAt, deliveredAt,
             dispatchOtp, podPhotoUrl, false, false, null);
    }

    public DeliveryResponse(UUID id, UUID orderId, String status, UUID riderId,
                            String riderName, Integer estimatedMinutes,
                            Instant dispatchedAt, Instant deliveredAt,
                            String dispatchOtp, String podPhotoUrl,
                            boolean coldChainRequired, boolean fragileRequired) {
        this(id, orderId, status, riderId, riderName, estimatedMinutes, dispatchedAt, deliveredAt,
             dispatchOtp, podPhotoUrl, coldChainRequired, fragileRequired, null);
    }
}
