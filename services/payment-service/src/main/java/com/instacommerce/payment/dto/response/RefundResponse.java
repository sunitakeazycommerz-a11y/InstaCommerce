package com.instacommerce.payment.dto.response;

import java.util.UUID;

public record RefundResponse(
    UUID refundId,
    String status,
    long amountCents
) {
}
