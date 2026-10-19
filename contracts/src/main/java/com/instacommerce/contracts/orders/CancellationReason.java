package com.instacommerce.contracts.orders;

/**
 * Structured taxonomy for order cancellation reasons.
 */
public enum CancellationReason {
    CUSTOMER_REQUEST,
    PAYMENT_FAILED,
    INVENTORY_SHORTAGE,
    ETA_UNACCEPTABLE,
    FRAUD_SUSPECTED
}
