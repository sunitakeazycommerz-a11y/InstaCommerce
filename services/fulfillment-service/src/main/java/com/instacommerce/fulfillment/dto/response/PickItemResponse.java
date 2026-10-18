package com.instacommerce.fulfillment.dto.response;

import java.time.Instant;
import java.util.UUID;

public record PickItemResponse(
    UUID id,
    UUID productId,
    String productName,
    String sku,
    int quantity,
    int pickedQty,
    long unitPriceCents,
    long lineTotalCents,
    String status,
    UUID substituteProductId,
    String note,
    Instant updatedAt,
    String aisle,
    Integer shelf,
    String bin,
    Integer pickSequence,
    String barcode,
    Integer tareWeightGrams
) {
    public PickItemResponse(UUID id, UUID productId, String productName, String sku,
                            int quantity, int pickedQty, long unitPriceCents,
                            long lineTotalCents, String status, UUID substituteProductId,
                            String note, Instant updatedAt) {
        this(id, productId, productName, sku, quantity, pickedQty, unitPriceCents,
             lineTotalCents, status, substituteProductId, note, updatedAt,
             null, null, null, null, null, null);
    }
}
