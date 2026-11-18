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
    BigDecimal dropoffLng,
    String orderId
) {
    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
            .userId(userId)
            .storeId(storeId)
            .items(items)
            .subtotalCents(subtotalCents)
            .discountCents(discountCents)
            .deliveryFeeCents(deliveryFeeCents)
            .totalCents(totalCents)
            .currency(currency)
            .couponCode(couponCode)
            .reservationId(reservationId)
            .paymentId(paymentId)
            .deliveryAddressId(deliveryAddressId)
            .paymentMethodId(paymentMethodId)
            .quoteId(quoteId)
            .quoteToken(quoteToken)
            .idempotencyKey(idempotencyKey)
            .dropoffLat(dropoffLat)
            .dropoffLng(dropoffLng)
            .orderId(orderId);
    }

    public OrderCreateRequest withIdempotencyKey(String idempotencyKey) {
        return toBuilder().idempotencyKey(idempotencyKey).build();
    }

    public static final class Builder {
        private String userId;
        private String storeId;
        private List<CartItem> items;
        private long subtotalCents;
        private long discountCents;
        private long deliveryFeeCents;
        private long totalCents;
        private String currency;
        private String couponCode;
        private String reservationId;
        private String paymentId;
        private String deliveryAddressId;
        private String paymentMethodId;
        private String quoteId;
        private String quoteToken;
        private String idempotencyKey;
        private BigDecimal dropoffLat;
        private BigDecimal dropoffLng;
        private String orderId;

        private Builder() {
        }

        public Builder userId(String userId) { this.userId = userId; return this; }
        public Builder storeId(String storeId) { this.storeId = storeId; return this; }
        public Builder items(List<CartItem> items) { this.items = items; return this; }
        public Builder subtotalCents(long subtotalCents) { this.subtotalCents = subtotalCents; return this; }
        public Builder discountCents(long discountCents) { this.discountCents = discountCents; return this; }
        public Builder deliveryFeeCents(long deliveryFeeCents) { this.deliveryFeeCents = deliveryFeeCents; return this; }
        public Builder totalCents(long totalCents) { this.totalCents = totalCents; return this; }
        public Builder currency(String currency) { this.currency = currency; return this; }
        public Builder couponCode(String couponCode) { this.couponCode = couponCode; return this; }
        public Builder reservationId(String reservationId) { this.reservationId = reservationId; return this; }
        public Builder paymentId(String paymentId) { this.paymentId = paymentId; return this; }
        public Builder deliveryAddressId(String deliveryAddressId) { this.deliveryAddressId = deliveryAddressId; return this; }
        public Builder paymentMethodId(String paymentMethodId) { this.paymentMethodId = paymentMethodId; return this; }
        public Builder quoteId(String quoteId) { this.quoteId = quoteId; return this; }
        public Builder quoteToken(String quoteToken) { this.quoteToken = quoteToken; return this; }
        public Builder idempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; return this; }
        public Builder dropoffLat(BigDecimal dropoffLat) { this.dropoffLat = dropoffLat; return this; }
        public Builder dropoffLng(BigDecimal dropoffLng) { this.dropoffLng = dropoffLng; return this; }
        public Builder orderId(String orderId) { this.orderId = orderId; return this; }

        public OrderCreateRequest build() {
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
                dropoffLng,
                orderId
            );
        }
    }
}
