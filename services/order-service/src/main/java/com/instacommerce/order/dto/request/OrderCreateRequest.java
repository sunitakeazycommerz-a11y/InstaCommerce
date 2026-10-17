package com.instacommerce.order.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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
    String idempotencyKey
) {
    public String effectiveDeliveryAddress() {
        if (deliveryAddress != null && !deliveryAddress.isBlank()) {
            return deliveryAddress;
        }
        return deliveryAddressId != null ? deliveryAddressId : "Default Address";
    }
}
