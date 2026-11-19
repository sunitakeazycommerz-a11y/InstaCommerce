package com.instacommerce.catalog.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateProductRequest(
    @Size(max = 50) String sku,
    @Size(max = 255) String name,
    String description,
    UUID categoryId,
    @Size(max = 100) String brand,
    @Positive Long basePriceCents,
    @Size(max = 3) String currency,
    @Size(max = 20) String unit,
    BigDecimal unitValue,
    Integer weightGrams,
    Boolean isActive,
    @Valid List<ProductImageRequest> images,
    Boolean isPerishable,
    @Size(max = 30) String temperatureZone,
    Integer vatRateBps,
    @Size(max = 20) String unitOfMeasure,
    UUID storeId
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
        this(sku, name, description, categoryId, brand, basePriceCents, currency, unit, unitValue, weightGrams, isActive, images, null, null, null, unit, null);
    }

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
        List<ProductImageRequest> images,
        Boolean isPerishable,
        String temperatureZone,
        Integer vatRateBps,
        String unitOfMeasure
    ) {
        this(sku, name, description, categoryId, brand, basePriceCents, currency, unit, unitValue, weightGrams, isActive, images, isPerishable, temperatureZone, vatRateBps, unitOfMeasure, null);
    }

    public String resolveUnit() {
        if (unitOfMeasure != null && !unitOfMeasure.isBlank()) {
            return unitOfMeasure;
        }
        return unit;
    }
}
