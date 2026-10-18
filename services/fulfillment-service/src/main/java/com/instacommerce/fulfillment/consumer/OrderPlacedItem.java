package com.instacommerce.fulfillment.consumer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderPlacedItem(
    UUID productId,
    String productName,
    String sku,
    int quantity,
    long unitPriceCents,
    long lineTotalCents,
    String aisle,
    Integer shelf,
    String bin,
    String barcode,
    Integer tareWeightGrams
) {
    public OrderPlacedItem(UUID productId, String productName, String sku, int quantity,
                           long unitPriceCents, long lineTotalCents) {
        this(productId, productName, sku, quantity, unitPriceCents, lineTotalCents,
             null, null, null, null, null);
    }
}
