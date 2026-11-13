package com.instacommerce.riderfleet.dto.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AssignRiderRequest(
    @NotNull UUID orderId,
    @NotNull UUID storeId,
    @NotNull BigDecimal pickupLat,
    @NotNull BigDecimal pickupLng,
    BigDecimal dropoffLat,
    BigDecimal dropoffLng
) {
    public AssignRiderRequest(
        UUID orderId,
        UUID storeId,
        BigDecimal pickupLat,
        BigDecimal pickupLng
    ) {
        this(orderId, storeId, pickupLat, pickupLng, null, null);
    }

    @JsonCreator
    public AssignRiderRequest(
        @JsonProperty("orderId") UUID orderId,
        @JsonProperty("storeId") Object storeIdObj,
        @JsonProperty("pickupLat") BigDecimal pickupLat,
        @JsonProperty("pickupLng") BigDecimal pickupLng,
        @JsonProperty("dropoffLat") BigDecimal dropoffLat,
        @JsonProperty("dropoffLng") BigDecimal dropoffLng
    ) {
        this(
            orderId,
            parseStoreId(storeIdObj),
            pickupLat,
            pickupLng,
            dropoffLat,
            dropoffLng
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
}
