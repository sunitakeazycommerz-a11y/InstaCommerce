package com.instacommerce.inventory.dto.request;

import java.util.Map;
import java.util.UUID;

public record PalletReceiptRequest(
    String receivedBy,
    Map<UUID, Integer> receivedQuantities
) {
    public PalletReceiptRequest(String receivedBy) {
        this(receivedBy, null);
    }
}
