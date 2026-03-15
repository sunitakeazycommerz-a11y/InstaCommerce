package com.instacommerce.fulfillment.exception;

/**
 * Thrown when an inter-service REST call fails and the downstream service
 * is considered unavailable (e.g. circuit breaker open or retries exhausted).
 */
public class ServiceUnavailableException extends RuntimeException {

    private final String serviceName;

    public ServiceUnavailableException(String serviceName, String message, Throwable cause) {
        super(message, cause);
        this.serviceName = serviceName;
    }

    public ServiceUnavailableException(String serviceName, String message) {
        super(message);
        this.serviceName = serviceName;
    }

    public String getServiceName() {
        return serviceName;
    }
}
