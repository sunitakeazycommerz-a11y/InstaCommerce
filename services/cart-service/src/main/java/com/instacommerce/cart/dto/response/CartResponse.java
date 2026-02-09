package com.instacommerce.cart.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CartResponse(
    UUID cartId,
    UUID userId,
    List<CartItemResponse> items,
    long subtotalCents,
    int itemCount,
    Instant expiresAt
) {
}
