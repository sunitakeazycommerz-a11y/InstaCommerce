package com.instacommerce.checkout.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record InventoryReservationResult(
    String reservationId,
    boolean reserved
) {
    @JsonCreator
    public InventoryReservationResult(
        @JsonProperty("reservationId") String reservationId,
        @JsonProperty("reserved") Boolean reserved
    ) {
        this(reservationId, reserved != null ? reserved : (reservationId != null && !reservationId.isBlank()));
    }
}
