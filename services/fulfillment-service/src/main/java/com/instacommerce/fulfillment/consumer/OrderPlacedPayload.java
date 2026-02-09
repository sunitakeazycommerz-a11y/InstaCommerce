package com.instacommerce.fulfillment.consumer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
}
