package com.instacommerce.inventory.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SafetyStockResponse(
    UUID storeId,
    UUID productId,
    BigDecimal zScore,
    BigDecimal stdDevDemand,
    int leadTimeDays,
    int calculatedSafetyStock,
    int reorderThreshold,
    int currentOnHand,
    int currentAvailable,
    boolean reorderNeeded,
    Instant lastReorderEventAt
) {}
