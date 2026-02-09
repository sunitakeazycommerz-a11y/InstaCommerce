package com.instacommerce.inventory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "reservation")
public class ReservationProperties {
    private int ttlMinutes = 5;
    private long expiryCheckIntervalMs = 30000;

    public int getTtlMinutes() {
        return ttlMinutes;
    }

    public void setTtlMinutes(int ttlMinutes) {
        this.ttlMinutes = ttlMinutes;
    }

    public long getExpiryCheckIntervalMs() {
        return expiryCheckIntervalMs;
    }

    public void setExpiryCheckIntervalMs(long expiryCheckIntervalMs) {
        this.expiryCheckIntervalMs = expiryCheckIntervalMs;
    }
}
