package com.instacommerce.wallet.client;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves order details from order-service.
 * Used by event consumers that receive events without userId.
 */
public interface OrderLookupClient {
    Optional<OrderSnapshot> findOrder(UUID orderId);
}
