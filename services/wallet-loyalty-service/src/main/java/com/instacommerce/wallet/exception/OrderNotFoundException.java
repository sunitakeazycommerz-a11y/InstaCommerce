package com.instacommerce.wallet.exception;

/**
 * Thrown when order-service confirms an order does not exist (HTTP 404).
 * Classified as non-retryable: the order will not appear on retry,
 * so the Kafka error handler routes the record to the DLT.
 */
public class OrderNotFoundException extends RuntimeException {

    private final String orderId;

    public OrderNotFoundException(String orderId) {
        super("Order not found in order-service: orderId=" + orderId);
        this.orderId = orderId;
    }

    public String getOrderId() {
        return orderId;
    }
}
