package com.instacommerce.checkout.dto;

import java.math.BigDecimal;
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
    String paymentMethodId,
    String quoteId,
    String quoteToken,
    String idempotencyKey,
    BigDecimal dropoffLat,
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
        String paymentMethodId,
        String quoteId,
        String quoteToken
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
            paymentMethodId,
            quoteId,
            quoteToken,
            null,
            null,
            null
        );
    }

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
            paymentMethodId,
            quoteId,
            quoteToken,
            idempotencyKey,
            null,
            null
        );
    }

    public OrderCreateRequest withIdempotencyKey(String idempotencyKey) {
        return new OrderCreateRequest(
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
            paymentMethodId,
            quoteId,
            quoteToken,
            idempotencyKey,
            dropoffLat,
            dropoffLng
        );
    }
}
