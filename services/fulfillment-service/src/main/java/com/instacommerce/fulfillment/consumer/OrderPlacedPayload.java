package com.instacommerce.fulfillment.consumer;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderPlacedPayload(
    UUID orderId,
    UUID userId,
    String storeId,
    UUID paymentId,
    List<OrderPlacedItem> items
) {
    @JsonCreator
    public OrderPlacedPayload(
        @JsonProperty("orderId") UUID orderId,
        @JsonProperty("userId") UUID userId,
        @JsonProperty("storeId") String storeId,
        @JsonProperty("paymentId") Object paymentIdObj,
        @JsonProperty("items") List<OrderPlacedItem> items
    ) {
        this(
            orderId,
            userId,
            storeId,
            parseUuid(paymentIdObj),
            items != null ? items : List.of()
        );
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
