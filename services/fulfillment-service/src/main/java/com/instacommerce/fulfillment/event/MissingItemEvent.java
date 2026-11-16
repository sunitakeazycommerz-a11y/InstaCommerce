package com.instacommerce.fulfillment.event;

import java.util.UUID;

public record MissingItemEvent(
    UUID productId,
    UUID storeId,
    int missingQty,
    String referenceId,
    UUID paymentId,
    long refundAmountCents,
    UUID substituteProductId,
    int substitutedQty
) {
    public MissingItemEvent(UUID productId,
                            UUID storeId,
                            int missingQty,
                            String referenceId,
                            UUID paymentId,
                            long refundAmountCents) {
        this(productId, storeId, missingQty, referenceId, paymentId, refundAmountCents, null, 0);
    }
}
