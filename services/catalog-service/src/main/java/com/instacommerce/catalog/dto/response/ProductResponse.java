package com.instacommerce.catalog.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProductResponse(
    UUID id,
    String sku,
    String name,
    String slug,
    String description,
    CategorySummaryResponse category,
    String brand,
    long basePriceCents,
    String currency,
    String unit,
    BigDecimal unitValue,
    Integer weightGrams,
    List<ProductImageResponse> images,
    boolean isActive,
    Instant createdAt
) {
}
