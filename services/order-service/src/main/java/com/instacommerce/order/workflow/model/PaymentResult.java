package com.instacommerce.order.workflow.model;

public record PaymentResult(
    String paymentId,
    String status
) {
}
