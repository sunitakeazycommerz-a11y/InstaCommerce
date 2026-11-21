package com.instacommerce.order.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CartItem(
    @NotNull UUID productId,
    String productName,
    String productSku,
    @NotNull @Positive Integer quantity,
    @NotNull @Positive Long unitPriceCents,
    Long lineTotalCents,
    Boolean coldChain,
    Boolean fragile,
    String storageTemperature,
    @PositiveOrZero Integer tareWeightGrams
) {
    public CartItem(UUID productId, Integer quantity, Long unitPriceCents) {
        this(productId, null, null, quantity, unitPriceCents, null, null, null, null, null);
    }

    public CartItem(UUID productId, String productName, String productSku,
                    Integer quantity, Long unitPriceCents, Long lineTotalCents) {
        this(productId, productName, productSku, quantity, unitPriceCents, lineTotalCents,
            null, null, null, null);
    }

    public String effectiveProductName() {
        return productName != null && !productName.isBlank() ? productName : "Product " + productId;
    }

    public String effectiveProductSku() {
        return productSku != null && !productSku.isBlank() ? productSku : "SKU-" + productId.toString().substring(0, 8);
    }

    public long effectiveLineTotalCents() {
        return lineTotalCents != null && lineTotalCents > 0 ? lineTotalCents : (long) quantity * unitPriceCents;
    }

    public boolean effectiveColdChain() {
        return Boolean.TRUE.equals(coldChain);
    }

    public boolean effectiveFragile() {
        return Boolean.TRUE.equals(fragile);
    }

    public int effectiveTareWeightGrams() {
        return tareWeightGrams != null && tareWeightGrams > 0 ? tareWeightGrams : 0;
    }
}
