package com.instacommerce.pricing.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Dynamic Surge Pricing Engine evaluating real-time dark store capacity,
 * rider fleet utilization, and weather demand signals.
 *
 * Enforces ethical surge guardrails (strict multiplier cap, transparent messaging).
 */
@Service
public class SurgePricingService {

    private static final Logger log = LoggerFactory.getLogger(SurgePricingService.class);

    // Hard ceiling for ethical surge pricing guardrail (max 2.0x)
    public static final BigDecimal MAX_SURGE_MULTIPLIER = new BigDecimal("2.00");
    public static final BigDecimal BASE_MULTIPLIER = BigDecimal.ONE;

    // Thresholds
    public static final double HIGH_CAPACITY_THRESHOLD = 0.85; // 85% rider utilization
    public static final int HIGH_QUEUE_THRESHOLD = 15; // 15 pending pick tasks
    public static final long BASE_DELIVERY_FEE_CENTS = 3000; // Rs 30.00 base delivery fee

    public record DarkStoreDemandSignal(
            UUID storeId,
            double riderUtilization, // 0.0 to 1.0
            int queueLength,
            boolean severeWeather,
            long updatedAtEpochMs
    ) {}

    public record SurgeAssessment(
            boolean isSurgeActive,
            BigDecimal surgeMultiplier,
            long surgeDeliveryFeeCents,
            String surgeReason,
            String customerBadgeText
    ) {}

    // In-memory local cache of store signals (updated via Redis or telemetry)
    private final Map<UUID, DarkStoreDemandSignal> storeSignals = new ConcurrentHashMap<>();

    /**
     * Updates real-time demand metrics for a dark store.
     */
    public void recordStoreSignal(UUID storeId, double riderUtilization, int queueLength, boolean severeWeather) {
        DarkStoreDemandSignal signal = new DarkStoreDemandSignal(
                storeId,
                Math.max(0.0, Math.min(1.0, riderUtilization)),
                Math.max(0, queueLength),
                severeWeather,
                System.currentTimeMillis()
        );
        storeSignals.put(storeId, signal);
        log.info("Updated surge demand signal for storeId={}: utilization={}, queue={}, weather={}",
                storeId, riderUtilization, queueLength, severeWeather);
    }

    /**
     * Evaluates surge conditions for a given store.
     */
    public SurgeAssessment evaluateSurge(UUID storeId) {
        if (storeId == null) {
            return new SurgeAssessment(false, BASE_MULTIPLIER, BASE_DELIVERY_FEE_CENTS, "NORMAL", null);
        }

        DarkStoreDemandSignal signal = storeSignals.get(storeId);
        if (signal == null) {
            return new SurgeAssessment(false, BASE_MULTIPLIER, BASE_DELIVERY_FEE_CENTS, "NORMAL", null);
        }

        boolean highDemand = signal.riderUtilization() >= HIGH_CAPACITY_THRESHOLD;
        boolean highQueue = signal.queueLength() >= HIGH_QUEUE_THRESHOLD;
        boolean badWeather = signal.severeWeather();

        if (!highDemand && !highQueue && !badWeather) {
            return new SurgeAssessment(false, BASE_MULTIPLIER, BASE_DELIVERY_FEE_CENTS, "NORMAL", null);
        }

        BigDecimal multiplier = BASE_MULTIPLIER;
        String reason;
        String badge;

        if (badWeather && highDemand) {
            multiplier = new BigDecimal("1.75");
            reason = "SEVERE_WEATHER_AND_PEAK_DEMAND";
            badge = "Rain Surge: High Demand in your area";
        } else if (badWeather) {
            multiplier = new BigDecimal("1.30");
            reason = "SEVERE_WEATHER";
            badge = "Weather Surge: Rain in your area";
        } else if (signal.riderUtilization() >= 0.95) {
            multiplier = new BigDecimal("1.60");
            reason = "CRITICAL_FLEET_UTILIZATION";
            badge = "Peak Demand Surge";
        } else {
            // Utilization between 85% and 95%
            multiplier = new BigDecimal("1.25");
            reason = "HIGH_FLEET_UTILIZATION";
            badge = "High Demand Surge";
        }

        // Enforce hard ceiling
        if (multiplier.compareTo(MAX_SURGE_MULTIPLIER) > 0) {
            multiplier = MAX_SURGE_MULTIPLIER;
        }

        long surgeDeliveryFee = BigDecimal.valueOf(BASE_DELIVERY_FEE_CENTS)
                .multiply(multiplier)
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();

        log.debug("Surge evaluated for storeId={}: active=true, multiplier={}, fee={}, reason={}",
                storeId, multiplier, surgeDeliveryFee, reason);

        return new SurgeAssessment(true, multiplier, surgeDeliveryFee, reason, badge);
    }
}
