package com.instacommerce.fulfillment.event;

import java.util.UUID;

public record ItemSubstitutedEvent(
    UUID originalProductId,
    UUID substituteProductId,
    UUID storeId,
    int substitutedQty,
    String referenceId
) {
}
