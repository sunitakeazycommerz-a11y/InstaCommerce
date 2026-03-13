package com.instacommerce.pricing.dto.request;

import java.util.UUID;

public record QuoteValidationRequest(
    UUID quoteId,
    String quoteToken,
    long totalCents,
    long subtotalCents,
    long discountCents
) {
}
