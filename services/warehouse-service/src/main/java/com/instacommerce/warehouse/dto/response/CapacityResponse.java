package com.instacommerce.warehouse.dto.response;

import java.time.LocalDate;
import java.util.UUID;

public record CapacityResponse(
    UUID storeId,
    LocalDate date,
    int hour,
    int currentOrders,
    int maxOrders,
    boolean canAcceptOrders,
    int maxConcurrentOrders,
    int currentPendingOrders,
    String throttleStatus,
    int etaExtensionMinutes
) {
    public CapacityResponse(
            UUID storeId,
            LocalDate date,
            int hour,
            int currentOrders,
            int maxOrders,
            boolean canAcceptOrders) {
        this(storeId, date, hour, currentOrders, maxOrders, canAcceptOrders,
             100, 0,
             canAcceptOrders ? "NORMAL" : "PAUSED", 0);
    }
}
