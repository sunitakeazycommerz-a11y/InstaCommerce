package com.instacommerce.order.workflow.model;

import com.instacommerce.order.dto.request.CartItem;
import java.util.List;
import java.util.UUID;

public final class CreateOrderCommand {
    private final UUID userId;
    private final String storeId;
    private final List<CartItem> items;
    private final long subtotalCents;
    private final long discountCents;
    private final long totalCents;
    private final String currency;
    private final String couponCode;
    private final UUID reservationId;
    private final UUID paymentId;
    private final String idempotencyKey;
    private final String deliveryAddress;

    private CreateOrderCommand(Builder builder) {
        this.userId = builder.userId;
        this.storeId = builder.storeId;
        this.items = builder.items;
        this.subtotalCents = builder.subtotalCents;
        this.discountCents = builder.discountCents;
        this.totalCents = builder.totalCents;
        this.currency = builder.currency;
        this.couponCode = builder.couponCode;
        this.reservationId = builder.reservationId;
        this.paymentId = builder.paymentId;
        this.idempotencyKey = builder.idempotencyKey;
        this.deliveryAddress = builder.deliveryAddress;
    }

    public static Builder builder() {
        return new Builder();
    }

    public UUID getUserId() {
        return userId;
    }

    public String getStoreId() {
        return storeId;
    }

    public List<CartItem> getItems() {
        return items;
    }

    public long getSubtotalCents() {
        return subtotalCents;
    }

    public long getDiscountCents() {
        return discountCents;
    }

    public long getTotalCents() {
        return totalCents;
    }

    public String getCurrency() {
        return currency;
    }

    public String getCouponCode() {
        return couponCode;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getDeliveryAddress() {
        return deliveryAddress;
    }

    public static final class Builder {
        private UUID userId;
        private String storeId;
        private List<CartItem> items;
        private long subtotalCents;
        private long discountCents;
        private long totalCents;
        private String currency;
        private String couponCode;
        private UUID reservationId;
        private UUID paymentId;
        private String idempotencyKey;
        private String deliveryAddress;

        public Builder userId(UUID userId) {
            this.userId = userId;
            return this;
        }

        public Builder storeId(String storeId) {
            this.storeId = storeId;
            return this;
        }

        public Builder items(List<CartItem> items) {
            this.items = items;
            return this;
        }

        public Builder subtotalCents(long subtotalCents) {
            this.subtotalCents = subtotalCents;
            return this;
        }

        public Builder discountCents(long discountCents) {
            this.discountCents = discountCents;
            return this;
        }

        public Builder totalCents(long totalCents) {
            this.totalCents = totalCents;
            return this;
        }

        public Builder currency(String currency) {
            this.currency = currency;
            return this;
        }

        public Builder couponCode(String couponCode) {
            this.couponCode = couponCode;
            return this;
        }

        public Builder reservationId(UUID reservationId) {
            this.reservationId = reservationId;
            return this;
        }

        public Builder paymentId(UUID paymentId) {
            this.paymentId = paymentId;
            return this;
        }

        public Builder idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        public Builder deliveryAddress(String deliveryAddress) {
            this.deliveryAddress = deliveryAddress;
            return this;
        }

        public CreateOrderCommand build() {
            return new CreateOrderCommand(this);
        }
    }
}
