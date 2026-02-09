package com.instacommerce.order.workflow.model;

import java.time.Instant;

public record ReserveResult(
    String reservationId,
    Instant expiresAt
) {
}
