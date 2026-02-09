package com.instacommerce.payment.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AuthorizeRequest(
    @NotNull UUID orderId,
    @Positive long amountCents,
    String currency,
    @NotBlank String idempotencyKey,
    String paymentMethod
) {
}
