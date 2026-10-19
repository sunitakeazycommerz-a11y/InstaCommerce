package com.instacommerce.order.consumer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FulfillmentOrderEvent(
    UUID orderId,
    UUID productId,
    Integer missingQty,
    Long refundCents
) {
    public FulfillmentOrderEvent(UUID orderId) {
        this(orderId, null, null, null);
    }
}
