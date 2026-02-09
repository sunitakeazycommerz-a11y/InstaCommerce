package com.instacommerce.order.domain.model;

public enum OrderStatus {
    PENDING,
    PLACED,
    PACKING,
    PACKED,
    OUT_FOR_DELIVERY,
    DELIVERED,
    CANCELLED,
    FAILED
}
