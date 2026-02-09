package com.instacommerce.fulfillment.event;

import java.util.UUID;

public record MissingItemEvent(
    UUID productId,
    String storeId,
    int missingQty,
    String referenceId,
    UUID paymentId,
    long refundAmountCents
) {
}
