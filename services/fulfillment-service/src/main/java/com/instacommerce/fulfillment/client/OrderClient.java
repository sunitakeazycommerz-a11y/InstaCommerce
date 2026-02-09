package com.instacommerce.fulfillment.client;

import java.util.UUID;

public interface OrderClient {
    void updateStatus(UUID orderId, String status, String note);
}
