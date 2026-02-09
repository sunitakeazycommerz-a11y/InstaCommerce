package com.instacommerce.checkout.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CheckoutRequest(
    @NotNull(message = "userId is required")
    String userId,

    @NotBlank(message = "paymentMethodId is required")
    String paymentMethodId,

    String couponCode,

    @NotBlank(message = "deliveryAddressId is required")
    String deliveryAddressId
) {}
