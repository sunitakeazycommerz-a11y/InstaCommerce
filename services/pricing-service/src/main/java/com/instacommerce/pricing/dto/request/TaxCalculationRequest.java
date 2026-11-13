package com.instacommerce.pricing.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record TaxCalculationRequest(
    @NotEmpty @Valid List<TaxLineItemRequest> items,
    @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal fallbackTaxRate
) {
    public TaxCalculationRequest(List<TaxLineItemRequest> items) {
        this(items, null);
    }

    public record TaxLineItemRequest(
        @NotNull UUID itemId,
        String itemName,
        @Min(0) long netAmountCents,
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal taxRate
    ) {
        public TaxLineItemRequest(UUID itemId, String itemName, long netAmountCents) {
            this(itemId, itemName, netAmountCents, null);
        }
    }
}
