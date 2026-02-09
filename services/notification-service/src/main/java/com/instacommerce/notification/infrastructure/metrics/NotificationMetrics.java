package com.instacommerce.notification.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class NotificationMetrics {
    private final Counter sentCounter;
    private final Counter failedCounter;
    private final Counter skippedCounter;

    public NotificationMetrics(MeterRegistry meterRegistry) {
        this.sentCounter = meterRegistry.counter("notification.sent");
        this.failedCounter = meterRegistry.counter("notification.failed");
        this.skippedCounter = meterRegistry.counter("notification.skipped");
    }

    public void incrementSent() {
        sentCounter.increment();
    }

    public void incrementFailed() {
        failedCounter.increment();
    }

    public void incrementSkipped() {
        skippedCounter.increment();
    }
}
