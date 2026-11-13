package com.instacommerce.pricing.dto.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record TaxCalculationResponse(
    List<LineItemTaxResponse> lineItems,
    long totalNetCents,
    long totalTaxCents,
    long totalGrossCents,
    BigDecimal fractionalRemainderCents
) {
    public record LineItemTaxResponse(
        UUID itemId,
        String itemName,
        long netAmountCents,
        long taxCents,
        long grossAmountCents,
        BigDecimal effectiveTaxRate
    ) {
    }
}
