package com.instacommerce.checkout.dto;

public record InventoryReservationResult(
    String reservationId,
    boolean reserved
) {}
