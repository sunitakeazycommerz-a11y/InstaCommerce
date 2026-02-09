package com.instacommerce.fulfillment.event;

import java.util.UUID;

public record OrderStatusUpdateEvent(UUID orderId, String status, String note) {
}
