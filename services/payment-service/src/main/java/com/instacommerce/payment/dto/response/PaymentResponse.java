package com.instacommerce.payment.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.UUID;

public record PaymentResponse(
    UUID paymentId,
    String status,
    boolean authorized,
    String declineReason,
    @JsonIgnore
    String pspReference
) {
    public PaymentResponse(UUID paymentId, String status, String pspReference) {
        this(
            paymentId,
            status,
            "AUTHORIZED".equalsIgnoreCase(status) || "CAPTURED".equalsIgnoreCase(status),
            null,
            pspReference
        );
    }
}
