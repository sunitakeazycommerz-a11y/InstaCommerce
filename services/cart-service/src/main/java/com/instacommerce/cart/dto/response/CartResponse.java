package com.instacommerce.cart.dto.response;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CartResponse(
    UUID cartId,
    UUID userId,
    List<CartItemResponse> items,
    long subtotalCents,
    int itemCount,
    Instant expiresAt,
    boolean valid,
    String storeId
) {
    public static final String DEFAULT_STORE_ID = "00000000-0000-0000-0000-000000000001";

    @JsonCreator
    public CartResponse(
        @JsonProperty("cartId") UUID cartId,
        @JsonProperty("userId") UUID userId,
        @JsonProperty("items") List<CartItemResponse> items,
        @JsonProperty("subtotalCents") long subtotalCents,
        @JsonProperty("itemCount") int itemCount,
        @JsonProperty("expiresAt") Instant expiresAt,
        @JsonProperty("valid") Boolean valid,
        @JsonProperty("storeId") String storeId
    ) {
        this(
            cartId,
            userId,
            items != null ? items : List.of(),
            subtotalCents,
            itemCount,
            expiresAt,
            valid != null ? valid : (items != null && !items.isEmpty()),
            storeId != null && !storeId.isBlank() ? storeId : DEFAULT_STORE_ID
        );
    }

    public CartResponse(
        UUID cartId,
        UUID userId,
        List<CartItemResponse> items,
        long subtotalCents,
        int itemCount,
        Instant expiresAt
    ) {
        this(cartId, userId, items, subtotalCents, itemCount, expiresAt, items != null && !items.isEmpty(), DEFAULT_STORE_ID);
    }
}
