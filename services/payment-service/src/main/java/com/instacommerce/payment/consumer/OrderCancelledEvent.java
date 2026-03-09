package com.instacommerce.payment.consumer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderCancelledEvent(
    String orderId,
    String userId,
    String paymentId,
    Long totalCents,
    String currency,
    String reason
) {
}
