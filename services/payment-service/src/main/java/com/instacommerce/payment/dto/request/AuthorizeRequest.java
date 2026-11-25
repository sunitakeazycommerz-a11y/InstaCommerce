package com.instacommerce.payment.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AuthorizeRequest(
    UUID orderId,
    @Positive long amountCents,
    String currency,
    @NotBlank String idempotencyKey,
    String paymentMethod,
    String paymentMethodId,
    UUID userId,
    String purpose
) {
    public AuthorizeRequest(UUID orderId, long amountCents, String currency, String idempotencyKey, String paymentMethod) {
        this(orderId, amountCents, currency, idempotencyKey, paymentMethod, null, null, null);
    }

    public AuthorizeRequest(UUID orderId, long amountCents, String currency, String idempotencyKey,
                            String paymentMethod, String paymentMethodId) {
        this(orderId, amountCents, currency, idempotencyKey, paymentMethod, paymentMethodId, null, null);
    }

    public AuthorizeRequest(UUID orderId, long amountCents, String currency, String idempotencyKey,
                            String paymentMethod, String paymentMethodId, UUID userId) {
        this(orderId, amountCents, currency, idempotencyKey, paymentMethod, paymentMethodId, userId, null);
    }

    public String effectivePaymentMethod() {
        return paymentMethod != null && !paymentMethod.isBlank() ? paymentMethod : paymentMethodId;
    }
}
