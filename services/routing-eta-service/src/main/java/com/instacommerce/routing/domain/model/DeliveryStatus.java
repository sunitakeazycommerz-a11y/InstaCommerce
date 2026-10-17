package com.instacommerce.routing.domain.model;

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
            case "ASSIGNED" -> RIDER_ASSIGNED;
            case "IN_TRANSIT" -> EN_ROUTE;
            case "PENDING" -> PENDING;
            case "RIDER_ASSIGNED" -> RIDER_ASSIGNED;
            case "PICKED_UP" -> PICKED_UP;
            case "EN_ROUTE" -> EN_ROUTE;
            case "NEAR_DESTINATION" -> NEAR_DESTINATION;
            case "DELIVERED" -> DELIVERED;
            case "FAILED" -> FAILED;
            default -> DeliveryStatus.valueOf(normalized);
        };
    }

    public boolean isTerminal() {
        return this == DELIVERED || this == FAILED;
    }

    public boolean isActive() {
        return this == RIDER_ASSIGNED || this == ASSIGNED || this == PICKED_UP || this == EN_ROUTE || this == IN_TRANSIT || this == NEAR_DESTINATION;
    }
}
