package com.instacommerce.order.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
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
    @PositiveOrZero long totalCents,
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
    BigDecimal dropoffLng,
    String orderId,
    @PositiveOrZero Long walletAmountCents
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
            String idempotencyKey,
            BigDecimal dropoffLat,
            BigDecimal dropoffLng,
            String orderId) {
        this(userId, storeId, items, subtotalCents, discountCents, deliveryFeeCents, totalCents,
            currency, couponCode, reservationId, paymentId, deliveryAddressId, deliveryAddress,
            paymentMethodId, quoteId, quoteToken, idempotencyKey, dropoffLat, dropoffLng, orderId, 0L);
    }

    public long effectiveWalletAmountCents() {
        return walletAmountCents != null ? Math.max(0L, walletAmountCents) : 0L;
    }

    public static Builder builder() {
        return new Builder();
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
        private String deliveryAddress;
        private String paymentMethodId;
        private String quoteId;
        private String quoteToken;
        private String idempotencyKey;
        private BigDecimal dropoffLat;
        private BigDecimal dropoffLng;
        private String orderId;
        private Long walletAmountCents;

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
        public Builder deliveryAddress(String deliveryAddress) { this.deliveryAddress = deliveryAddress; return this; }
        public Builder paymentMethodId(String paymentMethodId) { this.paymentMethodId = paymentMethodId; return this; }
        public Builder quoteId(String quoteId) { this.quoteId = quoteId; return this; }
        public Builder quoteToken(String quoteToken) { this.quoteToken = quoteToken; return this; }
        public Builder idempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; return this; }
        public Builder dropoffLat(BigDecimal dropoffLat) { this.dropoffLat = dropoffLat; return this; }
        public Builder dropoffLng(BigDecimal dropoffLng) { this.dropoffLng = dropoffLng; return this; }
        public Builder orderId(String orderId) { this.orderId = orderId; return this; }
        public Builder walletAmountCents(Long walletAmountCents) { this.walletAmountCents = walletAmountCents; return this; }

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
                deliveryAddress,
                paymentMethodId,
                quoteId,
                quoteToken,
                idempotencyKey,
                dropoffLat,
                dropoffLng,
                orderId,
                walletAmountCents
            );
        }
    }
}
