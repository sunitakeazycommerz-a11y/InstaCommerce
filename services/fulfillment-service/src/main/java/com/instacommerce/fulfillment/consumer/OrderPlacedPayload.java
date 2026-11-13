package com.instacommerce.fulfillment.consumer;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderPlacedPayload(
    UUID orderId,
    UUID userId,
    UUID storeId,
    UUID paymentId,
    List<OrderPlacedItem> items
) {
    @JsonCreator
    public OrderPlacedPayload(
        @JsonProperty("orderId") UUID orderId,
        @JsonProperty("userId") UUID userId,
        @JsonProperty("storeId") Object storeIdObj,
        @JsonProperty("paymentId") Object paymentIdObj,
        @JsonProperty("items") List<OrderPlacedItem> items
    ) {
        this(
            orderId,
            userId,
            parseStoreId(storeIdObj),
            parseUuid(paymentIdObj),
            items != null ? items : List.of()
        );
    }

    private static UUID parseStoreId(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof UUID u) {
            return u;
        }
        String str = obj.toString().trim();
        if (str.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(str);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(str.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static UUID parseUuid(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof UUID u) {
            return u;
        }
        String str = obj.toString().trim();
        if (str.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(str);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
