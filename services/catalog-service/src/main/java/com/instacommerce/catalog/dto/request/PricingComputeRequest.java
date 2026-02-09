package com.instacommerce.catalog.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PricingComputeRequest(
    @NotBlank String storeId,
    @Valid @NotEmpty List<PricingItemRequest> items,
    String couponCode,
    UUID userId
) {
}
