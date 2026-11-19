package com.instacommerce.warehouse.dto.response;

import java.util.UUID;

public record ZoneResolutionResponse(
    UUID storeId,
    String storeName,
    String pincode,
    String resolutionSource,
    boolean open,
    boolean canAcceptOrders,
    String throttleStatus,
    int etaExtensionMinutes
) {
}
