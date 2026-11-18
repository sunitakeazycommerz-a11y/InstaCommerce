package com.instacommerce.order.consumer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FulfillmentOrderEvent(
    UUID orderId,
    UUID productId,
    Integer missingQty,
    Long refundCents,
    Long newVersion,
    Long priceDeltaCents,
    List<OrderItemDelta> itemDeltas
) {
    public FulfillmentOrderEvent(UUID orderId) {
        this(orderId, null, null, null, null, null, null);
    }

    public FulfillmentOrderEvent(UUID orderId, UUID productId, Integer missingQty, Long refundCents) {
        this(orderId, productId, missingQty, refundCents, null, null, null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderItemDelta(
        UUID productId,
        Integer quantityDelta,
        Long priceDeltaCents
    ) {}
}
