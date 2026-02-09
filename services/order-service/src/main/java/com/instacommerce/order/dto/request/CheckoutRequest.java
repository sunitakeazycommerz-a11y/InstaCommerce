package com.instacommerce.order.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CheckoutRequest(
    @NotNull UUID userId,
    @NotBlank String storeId,
    @Valid @NotEmpty @Size(max = 50) List<CartItem> items,
    @NotNull @Positive Long subtotalCents,
    @NotNull @PositiveOrZero Long discountCents,
    @NotNull @Positive Long totalCents,
    @NotBlank @Size(min = 3, max = 3) String currency,
    @Size(max = 30) String couponCode,
    @NotBlank @Size(max = 64) String idempotencyKey,
    @Size(max = 500) String deliveryAddress
) {
}
