package com.instacommerce.catalog.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateProductRequest(
    String sku,
    String name,
    String description,
    UUID categoryId,
    String brand,
    @Positive Long basePriceCents,
    String currency,
    String unit,
    BigDecimal unitValue,
    Integer weightGrams,
    Boolean isActive,
    @Valid List<ProductImageRequest> images
) {
}
