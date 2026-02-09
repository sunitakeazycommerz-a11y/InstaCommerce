package com.instacommerce.fraud.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FraudCheckRequest(
        @NotNull UUID userId,
        @NotNull UUID orderId,
        @Positive long totalCents,
        String deviceFingerprint,
        String ipAddress,
        Double deliveryLat,
        Double deliveryLng,
        int itemCount,
        String paymentMethodType,
        boolean isNewUser
) {
}
