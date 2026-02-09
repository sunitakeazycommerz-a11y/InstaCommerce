package com.instacommerce.catalog.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PricingItemRequest(
    @NotNull UUID productId,
    @Min(1) int quantity
) {
}
