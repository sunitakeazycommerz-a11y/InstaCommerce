package com.instacommerce.pricing.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

public record PriceCalculationRequest(
    @NotEmpty @Valid List<CartItem> items,
    UUID userId,
    String couponCode,
    UUID storeId
) {
    public PriceCalculationRequest(List<CartItem> items, UUID userId, String couponCode) {
        this(items, userId, couponCode, null);
    }
}
