package com.instacommerce.order.client;

import java.util.UUID;

public record PaymentAuthorizeResponse(
    UUID paymentId,
    String status
) {
}
