package com.instacommerce.payment.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.UUID;

public record PaymentResponse(
    UUID paymentId,
    String status,
    boolean authorized,
    String declineReason,
    @JsonIgnore
    String pspReference,
    String paymentReference,
    long amountCents,
    String currency
) {
    public PaymentResponse(UUID paymentId, String status, String pspReference) {
        this(
            paymentId,
            status,
            "AUTHORIZED".equalsIgnoreCase(status) || "CAPTURED".equalsIgnoreCase(status),
            null,
            pspReference,
            paymentId != null ? paymentId.toString() : null,
            0L,
            "INR"
        );
    }

    public PaymentResponse(UUID paymentId, String status, String pspReference, long amountCents, String currency) {
        this(
            paymentId,
            status,
            "AUTHORIZED".equalsIgnoreCase(status) || "CAPTURED".equalsIgnoreCase(status),
            null,
            pspReference,
            paymentId != null ? paymentId.toString() : null,
            amountCents,
            currency != null ? currency : "INR"
        );
    }
}
