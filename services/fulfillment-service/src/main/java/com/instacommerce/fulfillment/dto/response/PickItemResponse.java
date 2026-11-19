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
    Integer tareWeightGrams,
    boolean coldChain,
    boolean fragile,
    String storageTemperature
) {
    public PickItemResponse(UUID id, UUID productId, String productName, String sku,
                            int quantity, int pickedQty, long unitPriceCents,
                            long lineTotalCents, String status, UUID substituteProductId,
                            String note, Instant updatedAt) {
        this(id, productId, productName, sku, quantity, pickedQty, unitPriceCents,
             lineTotalCents, status, substituteProductId, note, updatedAt,
             null, null, null, null, null, null, false, false, null);
    }

    public PickItemResponse(UUID id, UUID productId, String productName, String sku,
                            int quantity, int pickedQty, long unitPriceCents,
                            long lineTotalCents, String status, UUID substituteProductId,
                            String note, Instant updatedAt, String aisle, Integer shelf,
                            String bin, Integer pickSequence, String barcode, Integer tareWeightGrams) {
        this(id, productId, productName, sku, quantity, pickedQty, unitPriceCents,
             lineTotalCents, status, substituteProductId, note, updatedAt,
             aisle, shelf, bin, pickSequence, barcode, tareWeightGrams, false, false, null);
    }
}
