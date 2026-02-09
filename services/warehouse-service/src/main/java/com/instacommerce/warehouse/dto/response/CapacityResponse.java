package com.instacommerce.warehouse.dto.response;

import java.time.LocalDate;
import java.util.UUID;

public record CapacityResponse(
    UUID storeId,
    LocalDate date,
    int hour,
    int currentOrders,
    int maxOrders,
    boolean canAcceptOrders
) {
}
