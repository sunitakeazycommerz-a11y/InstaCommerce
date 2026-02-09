package com.instacommerce.cart.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AddItemRequest(
    @NotNull(message = "productId is required")
    UUID productId,

    @NotBlank(message = "productName is required")
    String productName,

    @Min(value = 0, message = "unitPriceCents must be non-negative")
    Long unitPriceCents,

    @NotNull(message = "quantity is required")
    @Min(value = 1, message = "quantity must be at least 1")
    Integer quantity
) {
}
