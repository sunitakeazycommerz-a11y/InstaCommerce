package com.instacommerce.order.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderCreateRequest(
    @NotNull String userId,
    String storeId,
    @NotEmpty List<CartItem> items,
    long subtotalCents,
    long discountCents,
    long deliveryFeeCents,
    long totalCents,
    String currency,
    String couponCode,
    String reservationId,
    String paymentId,
    String deliveryAddressId,
    String deliveryAddress,
    String paymentMethodId,
    String quoteId,
    String quoteToken,
    String idempotencyKey,
    @DecimalMin(value = "-90.0", message = "dropoffLat must be >= -90.0")
    @DecimalMax(value = "90.0", message = "dropoffLat must be <= 90.0")
    BigDecimal dropoffLat,
    @DecimalMin(value = "-180.0", message = "dropoffLng must be >= -180.0")
    @DecimalMax(value = "180.0", message = "dropoffLng must be <= 180.0")
    BigDecimal dropoffLng
) {
    public OrderCreateRequest(
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
        String deliveryAddress,
        String paymentMethodId,
        String quoteId,
        String quoteToken,
        String idempotencyKey
    ) {
        this(
            userId,
            storeId,
            items,
            subtotalCents,
            discountCents,
            deliveryFeeCents,
            totalCents,
            currency,
            couponCode,
            reservationId,
            paymentId,
            deliveryAddressId,
            deliveryAddress,
            paymentMethodId,
            quoteId,
            quoteToken,
            idempotencyKey,
            null,
            null
        );
    }

    @JsonIgnore
    @AssertTrue(message = "dropoffLat and dropoffLng must both be provided together")
    public boolean isDropoffCoordinatesPairValid() {
        return (dropoffLat == null) == (dropoffLng == null);
    }

    public String effectiveDeliveryAddress() {
        if (deliveryAddress != null && !deliveryAddress.isBlank()) {
            return deliveryAddress;
        }
        return deliveryAddressId != null ? deliveryAddressId : "Default Address";
    }
}
