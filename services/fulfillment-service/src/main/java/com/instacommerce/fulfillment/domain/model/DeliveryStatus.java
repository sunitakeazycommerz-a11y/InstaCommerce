package com.instacommerce.fulfillment.domain.model;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum DeliveryStatus {
    PENDING,
    ASSIGNED,
    RIDER_ASSIGNED,
    PICKED_UP,
    IN_TRANSIT,
    EN_ROUTE,
    NEAR_DESTINATION,
    DELIVERED,
    FAILED;

    @JsonCreator
    public static DeliveryStatus fromString(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase();
        return switch (normalized) {
            case "PENDING" -> PENDING;
            case "ASSIGNED", "RIDER_ASSIGNED" -> ASSIGNED;
            case "PICKED_UP" -> PICKED_UP;
            case "IN_TRANSIT", "EN_ROUTE" -> IN_TRANSIT;
            case "NEAR_DESTINATION" -> NEAR_DESTINATION;
            case "DELIVERED" -> DELIVERED;
            case "FAILED" -> FAILED;
            default -> DeliveryStatus.valueOf(normalized);
        };
    }

    public boolean isTerminal() {
        return this == DELIVERED || this == FAILED;
    }

    public boolean isInTransit() {
        return this == IN_TRANSIT || this == EN_ROUTE || this == NEAR_DESTINATION;
    }

    public boolean isAssigned() {
        return this == ASSIGNED || this == RIDER_ASSIGNED;
    }
}
