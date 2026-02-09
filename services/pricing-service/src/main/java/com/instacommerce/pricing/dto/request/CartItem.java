package com.instacommerce.pricing.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CartItem(
    @NotNull UUID productId,
    @Min(1) int quantity
) {
}
