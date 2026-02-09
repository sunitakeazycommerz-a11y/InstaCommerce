package com.instacommerce.checkout.dto;

import java.util.List;

public record OrderCreateRequest(
    String userId,
    String storeId,
    List<CartItem> items,
    long subtotalCents,
    long discountCents,
    long deliveryFeeCents,
    long totalCents,
    String currency,
    String couponCode,
    String reservationId,
    String paymentId,
    String deliveryAddressId,
    String paymentMethodId
) {}
