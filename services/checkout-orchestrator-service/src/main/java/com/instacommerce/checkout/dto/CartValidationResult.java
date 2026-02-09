package com.instacommerce.checkout.dto;

import java.util.List;

public record CartValidationResult(
    String userId,
    List<CartItem> items,
    boolean valid,
    String storeId
) {}
