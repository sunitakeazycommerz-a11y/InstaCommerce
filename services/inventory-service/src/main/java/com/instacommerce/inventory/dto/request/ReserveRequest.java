package com.instacommerce.inventory.dto.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ReserveRequest(
    String idempotencyKey,
    UUID storeId,
    @Valid @NotEmpty List<InventoryItemRequest> items
) {
    public static final UUID DEFAULT_STORE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @JsonCreator
    public ReserveRequest(
        @JsonProperty("idempotencyKey") String idempotencyKey,
        @JsonProperty("storeId") UUID storeId,
        @JsonProperty("items") List<InventoryItemRequest> items
    ) {
        this.idempotencyKey = (idempotencyKey != null && !idempotencyKey.isBlank())
            ? idempotencyKey : UUID.randomUUID().toString();
        this.storeId = storeId != null ? storeId : DEFAULT_STORE_ID;
        this.items = items != null ? items : List.of();
    }
}

