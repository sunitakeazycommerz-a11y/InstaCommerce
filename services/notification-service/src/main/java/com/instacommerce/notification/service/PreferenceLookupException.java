package com.instacommerce.notification.service;

public class PreferenceLookupException extends RuntimeException {
    public PreferenceLookupException(String message) {
        super(message);
    }

    public PreferenceLookupException(String message, Throwable cause) {
        super(message, cause);
    }
}
