package com.instacommerce.inventory.dto.response;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReserveResponse(
    UUID reservationId,
    Instant expiresAt,
    List<ReservedItemResponse> items,
    boolean reserved
) {
    @JsonCreator
    public ReserveResponse(
        @JsonProperty("reservationId") UUID reservationId,
        @JsonProperty("expiresAt") Instant expiresAt,
        @JsonProperty("items") List<ReservedItemResponse> items,
        @JsonProperty("reserved") Boolean reserved
    ) {
        this(
            reservationId,
            expiresAt,
            items != null ? items : List.of(),
            reserved != null ? reserved : (reservationId != null)
        );
    }

    public ReserveResponse(UUID reservationId, Instant expiresAt, List<ReservedItemResponse> items) {
        this(reservationId, expiresAt, items, true);
    }
}
