package com.instacommerce.pricing.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SurgePricingService {

    private static final Logger log = LoggerFactory.getLogger(SurgePricingService.class);

    public static final BigDecimal MAX_SURGE_MULTIPLIER = new BigDecimal("2.00");
    public static final BigDecimal BASE_MULTIPLIER = BigDecimal.ONE;

    public static final double HIGH_CAPACITY_THRESHOLD = 0.85;
    public static final int HIGH_QUEUE_THRESHOLD = 15;
    public static final long BASE_DELIVERY_FEE_CENTS = 3000;

    public record DarkStoreDemandSignal(
            UUID storeId,
            double riderUtilization,
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

    private final Map<UUID, DarkStoreDemandSignal> storeSignals = new ConcurrentHashMap<>();
    private final StoreSurgeStateStore storeSurgeStateStore;

    public SurgePricingService() {
        this((StoreSurgeStateStore) null);
    }

    @Autowired
    public SurgePricingService(ObjectProvider<StoreSurgeStateStore> storeSurgeStateStoreProvider) {
        this(storeSurgeStateStoreProvider != null ? storeSurgeStateStoreProvider.getIfAvailable() : null);
    }

    public SurgePricingService(StoreSurgeStateStore storeSurgeStateStore) {
        this.storeSurgeStateStore = storeSurgeStateStore;
    }

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

    public SurgeAssessment evaluateSurge(UUID storeId) {
        if (storeId == null) {
            return new SurgeAssessment(false, BASE_MULTIPLIER, BASE_DELIVERY_FEE_CENTS, "NORMAL", null);
        }

        SurgeAssessment staticAssessment = evaluateStaticSignal(storeSignals.get(storeId));
        Optional<StoreSurgeStateStore.StoreSurgeState> liveState = storeSurgeStateStore != null
                ? storeSurgeStateStore.getActiveState(storeId)
                : Optional.empty();

        if (liveState.isEmpty()) {
            return staticAssessment;
        }

        StoreSurgeStateStore.StoreSurgeState live = liveState.get();
        BigDecimal liveMultiplier = live.surgeMultiplier() != null ? live.surgeMultiplier() : BASE_MULTIPLIER;
        BigDecimal effectiveMultiplier = staticAssessment.isSurgeActive()
                ? liveMultiplier.max(staticAssessment.surgeMultiplier())
                : liveMultiplier;
        if (effectiveMultiplier.compareTo(MAX_SURGE_MULTIPLIER) > 0) {
            effectiveMultiplier = MAX_SURGE_MULTIPLIER;
        }

        String reason = staticAssessment.isSurgeActive()
                && staticAssessment.surgeMultiplier().compareTo(liveMultiplier) > 0
                ? staticAssessment.surgeReason()
                : live.reason();
        String badge = staticAssessment.customerBadgeText() != null
                ? staticAssessment.customerBadgeText()
                : resolveLiveBadge(live);

        long surgeDeliveryFee = calculateDeliveryFee(effectiveMultiplier);
        log.debug("Live+static surge evaluated for storeId={}: active=true, multiplier={}, fee={}, reason={}",
                storeId, effectiveMultiplier, surgeDeliveryFee, reason);
        return new SurgeAssessment(true, effectiveMultiplier, surgeDeliveryFee, reason, badge);
    }

    public static long calculateDeliveryFee(BigDecimal multiplier) {
        BigDecimal capped = multiplier != null ? multiplier : BASE_MULTIPLIER;
        if (capped.compareTo(MAX_SURGE_MULTIPLIER) > 0) {
            capped = MAX_SURGE_MULTIPLIER;
        }
        if (capped.compareTo(BASE_MULTIPLIER) < 0) {
            capped = BASE_MULTIPLIER;
        }
        return BigDecimal.valueOf(BASE_DELIVERY_FEE_CENTS)
                .multiply(capped)
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
    }

    private SurgeAssessment evaluateStaticSignal(DarkStoreDemandSignal signal) {
        if (signal == null) {
            return new SurgeAssessment(false, BASE_MULTIPLIER, BASE_DELIVERY_FEE_CENTS, "NORMAL", null);
        }

        boolean highDemand = signal.riderUtilization() >= HIGH_CAPACITY_THRESHOLD;
        boolean highQueue = signal.queueLength() >= HIGH_QUEUE_THRESHOLD;
        boolean badWeather = signal.severeWeather();

        if (!highDemand && !highQueue && !badWeather) {
            return new SurgeAssessment(false, BASE_MULTIPLIER, BASE_DELIVERY_FEE_CENTS, "NORMAL", null);
        }

        BigDecimal multiplier;
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
            multiplier = new BigDecimal("1.25");
            reason = "HIGH_FLEET_UTILIZATION";
            badge = "High Demand Surge";
        }

        if (multiplier.compareTo(MAX_SURGE_MULTIPLIER) > 0) {
            multiplier = MAX_SURGE_MULTIPLIER;
        }

        long surgeDeliveryFee = calculateDeliveryFee(multiplier);
        return new SurgeAssessment(true, multiplier, surgeDeliveryFee, reason, badge);
    }

    private static String resolveLiveBadge(StoreSurgeStateStore.StoreSurgeState live) {
        if (live.paused()) {
            return "Peak Store Capacity Surge";
        }
        if (live.throttled()) {
            return "High Store Demand Surge";
        }
        return "High Demand Surge";
    }
}
