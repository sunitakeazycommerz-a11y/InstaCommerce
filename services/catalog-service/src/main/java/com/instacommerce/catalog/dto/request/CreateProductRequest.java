package com.instacommerce.catalog.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateProductRequest(
    @NotBlank @Size(max = 50) String sku,
    @NotBlank @Size(max = 255) String name,
    String description,
    @NotNull UUID categoryId,
    @Size(max = 100) String brand,
    @NotNull @Positive Long basePriceCents,
    @Size(max = 3) String currency,
    @Size(max = 20) String unit,
    BigDecimal unitValue,
    Integer weightGrams,
    @Valid List<ProductImageRequest> images,
    Boolean isPerishable,
    @Size(max = 30) String temperatureZone,
    Integer vatRateBps,
    @Size(max = 20) String unitOfMeasure,
    UUID storeId
) {
    public CreateProductRequest(
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
        List<ProductImageRequest> images
    ) {
        this(sku, name, description, categoryId, brand, basePriceCents, currency, unit, unitValue, weightGrams, images, false, "AMBIENT", 0, unit, null);
    }

    public CreateProductRequest(
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
        List<ProductImageRequest> images,
        Boolean isPerishable,
        String temperatureZone,
        Integer vatRateBps,
        String unitOfMeasure
    ) {
        this(sku, name, description, categoryId, brand, basePriceCents, currency, unit, unitValue, weightGrams, images, isPerishable, temperatureZone, vatRateBps, unitOfMeasure, null);
    }

    public String resolveUnit() {
        if (unitOfMeasure != null && !unitOfMeasure.isBlank()) {
            return unitOfMeasure;
        }
        return unit != null && !unit.isBlank() ? unit : "piece";
    }
}
