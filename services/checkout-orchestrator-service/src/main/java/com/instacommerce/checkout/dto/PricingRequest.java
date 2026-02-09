package com.instacommerce.checkout.dto;

import java.util.List;

public record PricingRequest(
    String userId,
    List<CartItem> items,
    String storeId,
    String couponCode,
    String deliveryAddressId
) {}
