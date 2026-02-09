package com.instacommerce.fulfillment.client;

public record OrderStatusUpdateRequest(
    String status,
    String note
) {
}
