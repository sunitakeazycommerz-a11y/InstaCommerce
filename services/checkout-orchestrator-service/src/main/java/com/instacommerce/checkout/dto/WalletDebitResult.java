package com.instacommerce.checkout.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WalletDebitResult(
    String referenceId,
    boolean debited,
    long amountCents,
    long balanceAfterCents,
    String failureReason
) {
    @JsonCreator
    public WalletDebitResult(
        @JsonProperty("referenceId") String referenceId,
        @JsonProperty("debited") Boolean debited,
        @JsonProperty("type") String type,
        @JsonProperty("amountCents") Long amountCents,
        @JsonProperty("balanceAfterCents") Long balanceAfterCents,
        @JsonProperty("failureReason") String failureReason
    ) {
        this(
            referenceId,
            debited != null ? debited : "DEBIT".equalsIgnoreCase(type),
            amountCents != null ? amountCents : 0L,
            balanceAfterCents != null ? balanceAfterCents : 0L,
            failureReason
        );
    }

    public WalletDebitResult(String referenceId, boolean debited, long amountCents, String failureReason) {
        this(referenceId, debited, amountCents, 0L, failureReason);
    }
}
