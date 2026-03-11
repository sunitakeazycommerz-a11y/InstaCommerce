package com.instacommerce.payment.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when the payment gateway circuit breaker is open, indicating the PSP
 * is temporarily unavailable. Callers should distinguish this from
 * {@link PaymentGatewayException} (a direct PSP error) to allow appropriate
 * retry/backoff decisions at the orchestration layer.
 */
public class PaymentGatewayUnavailableException extends ApiException {
    public PaymentGatewayUnavailableException(String message, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "PSP_UNAVAILABLE", message);
        initCause(cause);
    }
}
