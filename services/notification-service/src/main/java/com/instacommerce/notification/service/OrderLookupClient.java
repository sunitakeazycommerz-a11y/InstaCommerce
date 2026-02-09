package com.instacommerce.notification.service;

import java.util.Optional;
import java.util.UUID;

public interface OrderLookupClient {
    Optional<OrderSnapshot> findOrder(UUID orderId);
}
