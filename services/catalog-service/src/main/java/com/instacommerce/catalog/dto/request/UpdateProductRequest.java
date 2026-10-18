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
    @Valid List<ProductImageRequest> images,
    Boolean isPerishable,
    String temperatureZone,
    Integer vatRateBps,
    String unitOfMeasure
) {
    public UpdateProductRequest(
        String sku,
        String name,
        String description,
        UUID categoryId,
        String brand,
        Long basePriceCents,
        String currency,
        String unit,
        BigDecimal unitValue,
        Integer weightGrams,
        Boolean isActive,
        List<ProductImageRequest> images
    ) {
        this(sku, name, description, categoryId, brand, basePriceCents, currency, unit, unitValue, weightGrams, isActive, images, null, null, null, unit);
    }

    public String resolveUnit() {
        if (unitOfMeasure != null && !unitOfMeasure.isBlank()) {
            return unitOfMeasure;
        }
        return unit;
    }
}
