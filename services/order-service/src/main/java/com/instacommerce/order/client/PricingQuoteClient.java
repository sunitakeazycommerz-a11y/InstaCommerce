package com.instacommerce.order.client;

import java.util.UUID;

public interface PricingQuoteClient {
    boolean validateQuote(UUID userId, UUID quoteId, String quoteToken, long totalCents, long subtotalCents, long discountCents);

    default boolean validateQuote(UUID quoteId, String quoteToken, long totalCents, long subtotalCents, long discountCents) {
        return validateQuote(null, quoteId, quoteToken, totalCents, subtotalCents, discountCents);
    }
}
