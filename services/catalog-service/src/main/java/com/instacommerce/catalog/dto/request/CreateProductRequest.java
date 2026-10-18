package com.instacommerce.catalog.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateProductRequest(
    @NotBlank String sku,
    @NotBlank String name,
    String description,
    @NotNull UUID categoryId,
    String brand,
    @NotNull @Positive Long basePriceCents,
    String currency,
    String unit,
    BigDecimal unitValue,
    Integer weightGrams,
    @Valid List<ProductImageRequest> images,
    Boolean isPerishable,
    String temperatureZone,
    Integer vatRateBps,
    String unitOfMeasure
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
        this(sku, name, description, categoryId, brand, basePriceCents, currency, unit, unitValue, weightGrams, images, false, "AMBIENT", 0, unit);
    }

    public String resolveUnit() {
        if (unitOfMeasure != null && !unitOfMeasure.isBlank()) {
            return unitOfMeasure;
        }
        return unit != null && !unit.isBlank() ? unit : "piece";
    }
}
