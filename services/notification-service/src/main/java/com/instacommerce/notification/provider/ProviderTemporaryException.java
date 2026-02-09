package com.instacommerce.notification.provider;

public class ProviderTemporaryException extends RuntimeException {
    public ProviderTemporaryException(String message, Throwable cause) {
        super(message, cause);
    }

    public ProviderTemporaryException(String message) {
        super(message);
    }
}
