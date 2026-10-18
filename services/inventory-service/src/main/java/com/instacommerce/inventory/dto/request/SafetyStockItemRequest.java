package com.instacommerce.inventory.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record SafetyStockItemRequest(
    @NotNull UUID productId,
    @NotNull @DecimalMin("0.5") BigDecimal zScore,
    @NotNull @DecimalMin("0.0") BigDecimal stdDevDemand,
    @Min(1) int leadTimeDays
) {}
