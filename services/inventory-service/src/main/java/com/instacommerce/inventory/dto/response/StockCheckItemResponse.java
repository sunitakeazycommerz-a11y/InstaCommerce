package com.instacommerce.inventory.dto.response;

import java.util.UUID;

public record StockCheckItemResponse(
    UUID productId,
    int available,
    int onHand,
    boolean sufficient
) {
}
