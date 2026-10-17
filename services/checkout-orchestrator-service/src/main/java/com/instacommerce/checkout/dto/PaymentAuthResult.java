package com.instacommerce.checkout.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentAuthResult(
    String paymentId,
    boolean authorized,
    String declineReason
) {
    @JsonCreator
    public PaymentAuthResult(
        @JsonProperty("paymentId") String paymentId,
        @JsonProperty("authorized") Boolean authorized,
        @JsonProperty("status") String status,
        @JsonProperty("declineReason") String declineReason
    ) {
        this(
            paymentId,
            authorized != null ? authorized : ("AUTHORIZED".equalsIgnoreCase(status) || "CAPTURED".equalsIgnoreCase(status)),
            declineReason
        );
    }
}
