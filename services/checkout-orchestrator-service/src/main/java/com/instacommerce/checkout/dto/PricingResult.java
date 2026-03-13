package com.instacommerce.checkout.dto;

public record PricingResult(
    long subtotalCents,
    long discountCents,
    long deliveryFeeCents,
    long totalCents,
    String currency,
    String quoteId,
    String quoteToken
) {}
