package com.instacommerce.pricing.dto.request;

import java.util.UUID;

public record QuoteValidationRequest(
    UUID quoteId,
    String quoteToken,
    long totalCents,
    long subtotalCents,
    long discountCents,
    UUID userId
) {
    public QuoteValidationRequest(UUID quoteId, String quoteToken, long totalCents, long subtotalCents, long discountCents) {
        this(quoteId, quoteToken, totalCents, subtotalCents, discountCents, null);
    }
}
