package com.instacommerce.order.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CartItem(
    @NotNull UUID productId,
    String productName,
    String productSku,
    @NotNull @Positive Integer quantity,
    @NotNull @Positive Long unitPriceCents,
    Long lineTotalCents
) {
    public CartItem(UUID productId, Integer quantity, Long unitPriceCents) {
        this(productId, null, null, quantity, unitPriceCents, null);
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
}
