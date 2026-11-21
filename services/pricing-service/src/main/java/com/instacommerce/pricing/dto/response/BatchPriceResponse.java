package com.instacommerce.pricing.dto.response;

import java.util.List;
import java.util.UUID;

public record BatchPriceResponse(
    List<PricedItem> items,
    List<UUID> unavailableProductIds
) {
    public BatchPriceResponse {
        items = items != null ? List.copyOf(items) : List.of();
        unavailableProductIds = unavailableProductIds != null ? List.copyOf(unavailableProductIds) : List.of();
    }
}
