package com.instacommerce.payment.exception;

/**
 * Signals a transient PSP communication failure (network timeout, connection reset)
 * that is safe to retry. Distinguished from permanent gateway errors like declines.
 */
public class PaymentGatewayTransientException extends PaymentGatewayException {
    public PaymentGatewayTransientException(String message, Throwable cause) {
        super(message, cause);
    }
}
