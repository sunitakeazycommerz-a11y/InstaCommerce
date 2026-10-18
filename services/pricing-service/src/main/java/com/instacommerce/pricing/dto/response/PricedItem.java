package com.instacommerce.pricing.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

public record PricedItem(
    UUID productId,
    @JsonProperty("unitPriceCents")
    long unitPriceCents,
    int quantity,
    long lineTotalCents,
    @JsonProperty("priceCents")
    long priceCents,
    String currency
) {
    public PricedItem(UUID productId, long unitPriceCents, int quantity, long lineTotalCents) {
        this(productId, unitPriceCents, quantity, lineTotalCents, unitPriceCents, "INR");
    }

    public PricedItem(UUID productId, long unitPriceCents, int quantity, long lineTotalCents, String currency) {
        this(productId, unitPriceCents, quantity, lineTotalCents, unitPriceCents, currency != null ? currency : "INR");
    }
}
