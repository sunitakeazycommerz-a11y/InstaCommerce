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
    String currency,
    UUID userId,
    String purpose
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
            "INR",
            null,
            null
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
            currency != null ? currency : "INR",
            null,
            null
        );
    }

    public PaymentResponse(UUID paymentId, String status, String pspReference, long amountCents,
                           String currency, UUID userId, String purpose) {
        this(
            paymentId,
            status,
            "AUTHORIZED".equalsIgnoreCase(status) || "CAPTURED".equalsIgnoreCase(status),
            null,
            pspReference,
            paymentId != null ? paymentId.toString() : null,
            amountCents,
            currency != null ? currency : "INR",
            userId,
            purpose
        );
    }
}
