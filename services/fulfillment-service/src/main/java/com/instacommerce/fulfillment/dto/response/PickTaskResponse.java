package com.instacommerce.fulfillment.dto.response;

import java.time.Instant;
import java.util.UUID;

public record PickTaskResponse(
    UUID id,
    UUID orderId,
    UUID storeId,
    String status,
    UUID pickerId,
    Instant startedAt,
    Instant completedAt,
    Instant createdAt,
    boolean coldChainRequired,
    boolean fragileRequired,
    Integer orderWeightGrams
) {
    public PickTaskResponse(UUID id, UUID orderId, UUID storeId, String status,
                            UUID pickerId, Instant startedAt, Instant completedAt, Instant createdAt) {
        this(id, orderId, storeId, status, pickerId, startedAt, completedAt, createdAt, false, false, null);
    }

    public PickTaskResponse(UUID id, UUID orderId, UUID storeId, String status,
                            UUID pickerId, Instant startedAt, Instant completedAt, Instant createdAt,
                            boolean coldChainRequired, boolean fragileRequired) {
        this(id, orderId, storeId, status, pickerId, startedAt, completedAt, createdAt,
             coldChainRequired, fragileRequired, null);
    }
}
