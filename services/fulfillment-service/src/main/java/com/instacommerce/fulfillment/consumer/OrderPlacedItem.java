package com.instacommerce.fulfillment.consumer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Locale;
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
    Integer tareWeightGrams,
    Boolean coldChain,
    Boolean fragile,
    String storageTemperature
) {
    public OrderPlacedItem(UUID productId, String productName, String sku, int quantity,
                           long unitPriceCents, long lineTotalCents) {
        this(productId, productName, sku, quantity, unitPriceCents, lineTotalCents,
             null, null, null, null, null, false, false, null);
    }

    public OrderPlacedItem(UUID productId, String productName, String sku, int quantity,
                           long unitPriceCents, long lineTotalCents, String aisle, Integer shelf,
                           String bin, String barcode, Integer tareWeightGrams) {
        this(productId, productName, sku, quantity, unitPriceCents, lineTotalCents,
             aisle, shelf, bin, barcode, tareWeightGrams, false, false, null);
    }

    public boolean isColdChainRequired() {
        if (Boolean.TRUE.equals(coldChain)) {
            return true;
        }
        if (storageTemperature == null || storageTemperature.isBlank()) {
            return false;
        }
        String normalized = storageTemperature.trim().toUpperCase(Locale.ROOT);
        return normalized.contains("CHILL") || normalized.contains("FROZEN")
            || normalized.contains("COLD") || normalized.contains("REFRIGERATED");
    }

    public boolean isFragileRequired() {
        return Boolean.TRUE.equals(fragile);
    }
}
