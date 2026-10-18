package com.instacommerce.inventory.dto.response;

import java.time.Instant;
import java.util.UUID;

public record StockBatchResponse(
    UUID id,
    UUID storeId,
    UUID productId,
    String batchNumber,
    int quantity,
    Instant expiryDate,
    boolean isDiscounted,
    int discountPercent,
    Instant markdownTriggeredAt
) {}
