package com.instacommerce.catalog.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProductChangedEvent(
    UUID productId,
    String sku,
    String name,
    String slug,
    UUID categoryId,
    boolean active,
    String description,
    String brand,
    String category,
    long priceCents,
    String imageUrl,
    boolean inStock,
    UUID storeId,
    Instant updatedAt,
    long basePriceCents,
    BigDecimal unitValue,
    String unitOfMeasure,
    Integer weightGrams,
    boolean isPerishable,
    String temperatureZone,
    Integer vatRateBps
) {
    public ProductChangedEvent(
        UUID productId,
        String sku,
        String name,
        String slug,
        UUID categoryId,
        boolean active,
        String description,
        String brand,
        String category,
        long priceCents,
        String imageUrl,
        boolean inStock,
        UUID storeId,
        Instant updatedAt
    ) {
        this(
            productId,
            sku,
            name,
            slug,
            categoryId,
            active,
            description,
            brand,
            category,
            priceCents,
            imageUrl,
            inStock,
            storeId,
            updatedAt,
            priceCents,
            BigDecimal.ONE,
            "piece",
            null,
            false,
            "AMBIENT",
            0
        );
    }
}
