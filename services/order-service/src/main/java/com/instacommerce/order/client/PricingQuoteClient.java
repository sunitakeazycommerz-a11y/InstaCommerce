package com.instacommerce.order.client;

import java.util.UUID;

public interface PricingQuoteClient {
    boolean validateQuote(UUID quoteId, String quoteToken, long totalCents, long subtotalCents, long discountCents);
}
