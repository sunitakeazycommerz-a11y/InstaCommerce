package com.instacommerce.checkout.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record CheckoutRequest(
    @NotNull(message = "userId is required")
    String userId,

    @NotBlank(message = "paymentMethodId is required")
    String paymentMethodId,

    String couponCode,

    @NotBlank(message = "deliveryAddressId is required")
    String deliveryAddressId,

    @DecimalMin(value = "-90.0", message = "dropoffLat must be >= -90.0")
    @DecimalMax(value = "90.0", message = "dropoffLat must be <= 90.0")
    BigDecimal dropoffLat,

    @DecimalMin(value = "-180.0", message = "dropoffLng must be >= -180.0")
    @DecimalMax(value = "180.0", message = "dropoffLng must be <= 180.0")
    BigDecimal dropoffLng
) {
    public CheckoutRequest(
        String userId,
        String paymentMethodId,
        String couponCode,
        String deliveryAddressId
    ) {
        this(userId, paymentMethodId, couponCode, deliveryAddressId, null, null);
    }

    @JsonIgnore
    @AssertTrue(message = "dropoffLat and dropoffLng must both be provided together")
    public boolean isDropoffCoordinatesPairValid() {
        return (dropoffLat == null) == (dropoffLng == null);
    }
}
