package com.instacommerce.riderfleet.consumer;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FulfillmentEventPayload(
    UUID orderId,
    UUID storeId,
    BigDecimal pickupLat,
    BigDecimal pickupLng,
    BigDecimal dropoffLat,
    BigDecimal dropoffLng,
    Integer orderWeightGrams
) {
    public FulfillmentEventPayload(
        UUID orderId,
        UUID storeId,
        BigDecimal pickupLat,
        BigDecimal pickupLng
    ) {
        this(orderId, storeId, pickupLat, pickupLng, null, null, null);
    }

    public FulfillmentEventPayload(
        UUID orderId,
        UUID storeId,
        BigDecimal pickupLat,
        BigDecimal pickupLng,
        BigDecimal dropoffLat,
        BigDecimal dropoffLng
    ) {
        this(orderId, storeId, pickupLat, pickupLng, dropoffLat, dropoffLng, null);
    }

    @JsonCreator
    public FulfillmentEventPayload(
        @JsonProperty("orderId") UUID orderId,
        @JsonProperty("storeId") Object storeIdObj,
        @JsonProperty("pickupLat") Object pickupLatObj,
        @JsonProperty("pickupLng") Object pickupLngObj,
        @JsonProperty("dropoffLat") Object dropoffLatObj,
        @JsonProperty("dropoffLng") Object dropoffLngObj,
        @JsonProperty("orderWeightGrams") Integer orderWeightGrams
    ) {
        this(
            orderId,
            parseStoreId(storeIdObj),
            parseCoordinate(pickupLatObj),
            parseCoordinate(pickupLngObj),
            parseCoordinate(dropoffLatObj),
            parseCoordinate(dropoffLngObj),
            orderWeightGrams
        );
    }

    private static BigDecimal parseCoordinate(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof BigDecimal bd) {
            return bd;
        }
        String str = obj.toString().trim();
        if (str.isEmpty() || "null".equalsIgnoreCase(str)) {
            return null;
        }
        try {
            return new BigDecimal(str);
        } catch (NumberFormatException ex) {
            return null;
        }
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
}
