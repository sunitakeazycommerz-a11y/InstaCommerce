package com.instacommerce.checkout.dto;

import java.util.List;

public record InventoryReserveRequest(
    String idempotencyKey,
    String storeId,
    List<CartItem> items
) {}
