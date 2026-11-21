package com.instacommerce.payment.consumer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderCancelledEvent(
    String orderId,
    String userId,
    String paymentId,
    Long totalCents,
    String currency,
    String reason,
    Long walletAmountCents,
    Long walletRefundCents,
    Long pspRefundCents,
    Long refundedCents
) {
    public OrderCancelledEvent(
        String orderId,
        String userId,
        String paymentId,
        Long totalCents,
        String currency,
        String reason
    ) {
        this(orderId, userId, paymentId, totalCents, currency, reason, null, null, null, null);
    }

    public OrderCancelledEvent(
        String orderId,
        String userId,
        String paymentId,
        Long totalCents,
        String currency,
        String reason,
        Long walletAmountCents,
        Long walletRefundCents,
        Long refundedCents
    ) {
        this(
            orderId,
            userId,
            paymentId,
            totalCents,
            currency,
            reason,
            walletAmountCents,
            walletRefundCents,
            null,
            refundedCents);
    }
}
