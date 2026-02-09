package com.instacommerce.order.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CartItem(
    @NotNull UUID productId,
    @NotBlank String productName,
    @NotBlank String productSku,
    @NotNull @Positive Integer quantity,
    @NotNull @Positive Long unitPriceCents,
    @NotNull @Positive Long lineTotalCents
) {
}
