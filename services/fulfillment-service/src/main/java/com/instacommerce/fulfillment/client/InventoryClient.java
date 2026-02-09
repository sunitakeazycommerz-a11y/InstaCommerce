package com.instacommerce.fulfillment.client;

import java.util.UUID;

public interface InventoryClient {
    void releaseStock(UUID productId, String storeId, int quantity, String reason, String referenceId);
}
