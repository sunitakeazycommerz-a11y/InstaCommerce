package com.instacommerce.payment.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Positive;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RefundRequest(
    @Positive long amountCents,
    String reason,
    String idempotencyKey
) {
}
