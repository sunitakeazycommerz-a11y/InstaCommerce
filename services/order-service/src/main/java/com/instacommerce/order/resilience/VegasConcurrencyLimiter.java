package com.instacommerce.order.resilience;

import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Adaptive concurrency limiter implementing the TCP Vegas gradient algorithm.
 * Dynamically adjusts in-flight request capacity based on observed round-trip latency (RTT).
 * When latency inflates due to downstream contention, in-flight limits contract, shedding
 * load with HTTP 429 to protect core stability.
 */
@Component
public class VegasConcurrencyLimiter {

    private static final Logger log = LoggerFactory.getLogger(VegasConcurrencyLimiter.class);

    private final AtomicInteger inFlight = new AtomicInteger(0);

    private volatile double currentLimit = 50.0;
    private final double minLimit = 10.0;
    private final double maxLimit = 500.0;
    private final double alpha = 2.0; // Queue threshold below which limit increases
    private final double beta = 6.0;  // Queue threshold above which limit decreases
    private final double smoothing = 0.2;

    private volatile double rttNoLoadMs = 10.0; // Baseline no-load RTT

    public boolean tryAcquire() {
        int current = inFlight.get();
        if (current >= (int) currentLimit) {
            log.warn("Adaptive concurrency limit reached: inFlight={}, limit={}", current, (int) currentLimit);
            return false;
        }
        inFlight.incrementAndGet();
        return true;
    }

    public void onSample(long rttMs) {
        try {
            if (rttMs <= 0) {
                return;
            }

            // Track lowest observed RTT as baseline no-load latency
            if (rttMs < rttNoLoadMs) {
                rttNoLoadMs = rttMs;
            }

            double gradient = rttNoLoadMs / Math.max(rttNoLoadMs, (double) rttMs);
            double queueSize = currentLimit * (1.0 - gradient);

            double newLimit = currentLimit;
            if (queueSize <= alpha) {
                // Queue is empty, can safely probe higher capacity
                newLimit = currentLimit + 1.0;
            } else if (queueSize >= beta) {
                // Queue is growing, throttle capacity to shed excess load
                newLimit = currentLimit - 2.0;
            }

            // Smooth adjustment and clamp to bounds
            double adjustedLimit = (currentLimit * (1.0 - smoothing)) + (newLimit * smoothing);
            currentLimit = Math.max(minLimit, Math.min(maxLimit, adjustedLimit));
        } finally {
            inFlight.decrementAndGet();
        }
    }

    public void releaseWithoutSample() {
        inFlight.decrementAndGet();
    }

    public int getInFlight() {
        return inFlight.get();
    }

    public double getCurrentLimit() {
        return currentLimit;
    }

    public void setCurrentLimitForTest(double limit) {
        this.currentLimit = limit;
    }

    public double getRttNoLoadMs() {
        return rttNoLoadMs;
    }
}
