package com.instacommerce.order.client;

import java.util.List;

public record InventoryReserveRequest(
    String idempotencyKey,
    String storeId,
    List<InventoryItemRequest> items
) {
}
