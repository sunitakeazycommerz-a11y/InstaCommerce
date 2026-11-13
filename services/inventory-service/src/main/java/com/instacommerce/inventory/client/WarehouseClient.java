package com.instacommerce.inventory.client;

import java.util.UUID;

public interface WarehouseClient {
    /**
     * Validates that the store exists and is in ACTIVE operational status.
     * Throws StoreNotFoundException if store does not exist.
     * Throws StoreInactiveException if store is in MAINTENANCE, CLOSED, or INACTIVE status.
     */
    void validateStoreActive(UUID storeId);

    /**
     * Checks whether the dark store is currently within operating hours.
     */
    boolean isStoreOpen(UUID storeId);

    /**
     * Evicts the cached store status for immediate real-time invalidation.
     */
    void evictStore(UUID storeId);
}
