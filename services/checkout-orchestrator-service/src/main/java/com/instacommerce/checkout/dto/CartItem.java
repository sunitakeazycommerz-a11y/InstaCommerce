package com.instacommerce.checkout.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CartItem(
    @NotBlank(message = "productId is required")
    String productId,
    @NotNull(message = "quantity is required")
    @Min(value = 1, message = "quantity must be at least 1")
    Integer quantity,
    @Min(value = 0, message = "unitPriceCents must be non-negative")
    long unitPriceCents
) {
    @JsonCreator
    public CartItem(
        @JsonProperty("productId") String productId,
        @JsonProperty("quantity") int quantity,
        @JsonProperty("unitPriceCents") long unitPriceCents
    ) {
        this(productId, Integer.valueOf(quantity), unitPriceCents);
    }
}
