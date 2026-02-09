package com.instacommerce.inventory.dto.response;

import java.util.UUID;

public record ReservedItemResponse(
    UUID productId,
    int quantity
) {
}
