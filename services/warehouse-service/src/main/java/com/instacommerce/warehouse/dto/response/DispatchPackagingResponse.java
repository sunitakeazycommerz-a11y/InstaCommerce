package com.instacommerce.warehouse.dto.response;

import java.time.Instant;

public record DispatchPackagingResponse(
    String orderId,
    String storageType,
    boolean verified,
    String message,
    Instant verifiedAt
) {
}
