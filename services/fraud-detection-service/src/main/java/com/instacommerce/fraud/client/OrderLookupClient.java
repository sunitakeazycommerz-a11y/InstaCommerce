package com.instacommerce.fraud.client;

import java.util.Optional;
import java.util.UUID;

public interface OrderLookupClient {

    Optional<OrderSnapshot> findOrder(UUID orderId);
}
