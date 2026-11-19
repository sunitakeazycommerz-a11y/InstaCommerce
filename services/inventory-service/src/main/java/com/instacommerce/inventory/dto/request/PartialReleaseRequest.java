package com.instacommerce.inventory.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PartialReleaseRequest(
    @NotNull UUID reservationId,
    @Valid @NotEmpty List<InventoryItemRequest> items,
    String releaseKey
) {
    public PartialReleaseRequest(UUID reservationId, List<InventoryItemRequest> items) {
        this(reservationId, items, null);
    }
}
