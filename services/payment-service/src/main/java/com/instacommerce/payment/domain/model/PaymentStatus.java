package com.instacommerce.payment.domain.model;

public enum PaymentStatus {
    AUTHORIZE_PENDING,
    AUTHORIZED,
    CAPTURE_PENDING,
    CAPTURED,
    VOID_PENDING,
    VOIDED,
    PARTIALLY_REFUNDED,
    REFUNDED,
    FAILED,
    DISPUTED
}
