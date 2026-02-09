package com.instacommerce.riderfleet.consumer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FulfillmentEventPayload(
    UUID orderId,
    UUID storeId,
    BigDecimal pickupLat,
    BigDecimal pickupLng
) {
}
