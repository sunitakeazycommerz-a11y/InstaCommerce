package com.instacommerce.checkout.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PricingResult(
    long subtotalCents,
    long discountCents,
    long deliveryFeeCents,
    long totalCents,
    String currency,
    String quoteId,
    String quoteToken
) {
    public static final String DEFAULT_CURRENCY = "INR";

    @JsonCreator
    public PricingResult(
        @JsonProperty("subtotalCents") long subtotalCents,
        @JsonProperty("discountCents") long discountCents,
        @JsonProperty("deliveryFeeCents") Long deliveryFeeCents,
        @JsonProperty("totalCents") long totalCents,
        @JsonProperty("currency") String currency,
        @JsonProperty("quoteId") String quoteId,
        @JsonProperty("quoteToken") String quoteToken
    ) {
        this(
            subtotalCents,
            discountCents,
            deliveryFeeCents != null ? deliveryFeeCents : 0L,
            totalCents,
            currency != null && !currency.isBlank() ? currency : DEFAULT_CURRENCY,
            quoteId,
            quoteToken
        );
    }
}
