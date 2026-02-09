package com.instacommerce.checkout.dto;

public record PaymentAuthResult(
    String paymentId,
    boolean authorized,
    String declineReason
) {}
