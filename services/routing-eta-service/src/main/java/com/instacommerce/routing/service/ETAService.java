package com.instacommerce.routing.service;

import com.instacommerce.routing.config.RoutingProperties;
import com.instacommerce.routing.domain.model.RoutingBarrier;
import com.instacommerce.routing.domain.model.TrafficCongestionFeed;
import com.instacommerce.routing.domain.model.WeatherCondition;
import com.instacommerce.routing.dto.response.ETAEstimate;
import com.instacommerce.routing.dto.response.ETAResponse;
import com.instacommerce.routing.repository.RoutingBarrierRepository;
import com.instacommerce.routing.repository.TrafficCongestionFeedRepository;
import com.instacommerce.routing.repository.WeatherConditionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class ETAService {

    private static final double EARTH_RADIUS_KM = 6371.0;
    private static final int CACHE_KEY_SCALE = 4;
    private static final Set<String> MONSOON_CONDITION_TYPES = Set.of(
        "RAIN", "LIGHT_RAIN", "MODERATE_RAIN", "HEAVY_RAIN", "MONSOON", "STORM", "CYCLONE"
    );

    private final RoutingProperties routingProperties;
    private final RoutingBarrierRepository barrierRepository;
    private final WeatherConditionRepository weatherRepository;
    private final TrafficCongestionFeedRepository trafficFeedRepository;
    private final Clock clock;

    public ETAService(RoutingProperties routingProperties) {
        this(routingProperties, (RoutingBarrierRepository) null, null, null, Clock.systemUTC());
    }

    @Autowired
    public ETAService(RoutingProperties routingProperties,
                      ObjectProvider<RoutingBarrierRepository> barrierRepositoryProvider,
                      ObjectProvider<WeatherConditionRepository> weatherRepositoryProvider,
                      ObjectProvider<TrafficCongestionFeedRepository> trafficFeedRepositoryProvider,
                      ObjectProvider<Clock> clockProvider) {
        this(
            routingProperties,
            barrierRepositoryProvider != null ? barrierRepositoryProvider.getIfAvailable() : null,
            weatherRepositoryProvider != null ? weatherRepositoryProvider.getIfAvailable() : null,
            trafficFeedRepositoryProvider != null ? trafficFeedRepositoryProvider.getIfAvailable() : null,
            clockProvider != null ? clockProvider.getIfAvailable(Clock::systemUTC) : Clock.systemUTC()
        );
    }

    public ETAService(RoutingProperties routingProperties,
                      RoutingBarrierRepository barrierRepository,
                      WeatherConditionRepository weatherRepository,
                      TrafficCongestionFeedRepository trafficFeedRepository) {
        this(routingProperties, barrierRepository, weatherRepository, trafficFeedRepository, Clock.systemUTC());
    }

    public ETAService(RoutingProperties routingProperties,
                      RoutingBarrierRepository barrierRepository,
                      WeatherConditionRepository weatherRepository,
                      TrafficCongestionFeedRepository trafficFeedRepository,
                      Clock clock) {
        this.routingProperties = routingProperties;
        this.barrierRepository = barrierRepository;
        this.weatherRepository = weatherRepository;
        this.trafficFeedRepository = trafficFeedRepository;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Cacheable(value = "eta", key = "#root.target.cacheKey(#fromLat, #fromLng, #toLat, #toLng)")
    public ETAResponse calculateETA(double fromLat, double fromLng, double toLat, double toLng) {
        return calculateETA(fromLat, fromLng, toLat, toLng, null, "MOTORCYCLE", 0.0);
    }

    public ETAResponse calculateETA(double fromLat, double fromLng, double toLat, double toLng,
                                    UUID storeId, String vehicleType, double inclinePercent) {
        return calculateETA(fromLat, fromLng, toLat, toLng, storeId, vehicleType, inclinePercent, 0);
    }

    public ETAResponse calculateETA(double fromLat, double fromLng, double toLat, double toLng,
                                    UUID storeId, String vehicleType, double inclinePercent, int batchStops) {
        ETAEstimate estimate = calculateETAWithConfidence(
            fromLat, fromLng, toLat, toLng, storeId, vehicleType, inclinePercent, batchStops);
        return new ETAResponse(
            estimate.etaMinutes(),
            estimate.distanceKm(),
            estimate.calculatedAt());
    }

    public ETAEstimate calculateETAWithConfidence(double fromLat, double fromLng,
                                                  double toLat, double toLng) {
        return calculateETAWithConfidence(fromLat, fromLng, toLat, toLng, null, "MOTORCYCLE", 0.0, 0);
    }

    public ETAEstimate calculateETAWithConfidence(double fromLat, double fromLng,
                                                  double toLat, double toLng,
                                                  UUID storeId, String vehicleType,
                                                  double inclinePercent) {
        return calculateETAWithConfidence(fromLat, fromLng, toLat, toLng, storeId, vehicleType, inclinePercent, 0);
    }

    public ETAEstimate calculateETAWithConfidence(double fromLat, double fromLng,
                                                  double toLat, double toLng,
                                                  UUID storeId, String vehicleType,
                                                  double inclinePercent, int batchStops) {
        return calculateETAWithConfidence(
            fromLat, fromLng, toLat, toLng, storeId, vehicleType, inclinePercent, batchStops, Instant.now(clock));
    }

    public ETAEstimate calculateETAWithConfidence(double fromLat, double fromLng,
                                                  double toLat, double toLng,
                                                  UUID storeId, String vehicleType,
                                                  double inclinePercent, int batchStops,
                                                  Instant evaluationTime) {
        Instant calculatedAt = evaluationTime != null ? evaluationTime : Instant.now(clock);
        double straightLineKm = haversineDistance(fromLat, fromLng, toLat, toLng);
        double roadDistanceKm = straightLineKm * routingProperties.getEta().getRoadDistanceMultiplier();

        BarrierImpact barrierImpact = evaluateBarriers(fromLat, fromLng, toLat, toLng, calculatedAt);
        roadDistanceKm += barrierImpact.detourKm();

        double avgSpeedKmh = adjustedSpeedKmh(calculatedAt);
        avgSpeedKmh = applyTrafficCongestion(avgSpeedKmh, storeId);

        WeatherImpact weatherImpact = evaluateWeather(storeId);
        double travelMinutes = (roadDistanceKm / avgSpeedKmh) * 60.0 * weatherImpact.multiplier();

        InclineImpact inclineImpact = evaluateIncline(vehicleType, inclinePercent);
        travelMinutes *= inclineImpact.multiplier();

        int prepTimeMinutes = Math.max(0, routingProperties.getEta().getPreparationTimeMinutes());
        int safeBatchStops = Math.max(0, batchStops);
        int dwellMinutes = safeBatchStops * Math.max(0, routingProperties.getEta().getBatchStopDwellMinutes());

        double rawTotalMinutes = travelMinutes + prepTimeMinutes + barrierImpact.detourMinutes() + dwellMinutes;
        int etaMinutes = Math.max(1, (int) Math.ceil(rawTotalMinutes));

        var breach = routingProperties.getBreach();
        int etaLow = Math.min(etaMinutes, Math.max(1, (int) Math.round(etaMinutes * breach.getEtaLowMultiplier())));
        double uncertaintyBoost = (weatherImpact.monsoonDelayActive() || barrierImpact.detourDetected() || safeBatchStops > 0)
            ? 1.1 : 1.0;
        int etaHigh = Math.max(etaMinutes, (int) Math.round(etaMinutes * breach.getEtaHighMultiplier() * uncertaintyBoost));

        double breachProbability = computeBreachProbability(rawTotalMinutes, breach.getSlaThresholdMinutes());

        return new ETAEstimate(
            etaMinutes, etaLow, etaHigh,
            BigDecimal.valueOf(roadDistanceKm).setScale(3, RoundingMode.HALF_UP),
            breachProbability, calculatedAt,
            barrierImpact.detourDetected(), weatherImpact.monsoonDelayActive(),
            weatherImpact.delayBanner(), inclineImpact.vehicleRecommendation(),
            safeBatchStops, dwellMinutes);
    }

    private BarrierImpact evaluateBarriers(double fromLat, double fromLng, double toLat, double toLng, Instant asOf) {
        boolean detourDetected = false;
        double detourKm = 0.0;
        int detourMinutes = 0;
        if (barrierRepository != null) {
            List<RoutingBarrier> activeBarriers = barrierRepository.findByIsActiveTrue();
            for (RoutingBarrier barrier : activeBarriers) {
                if (barrier.intersects(fromLat, fromLng, toLat, toLng, asOf)) {
                    detourDetected = true;
                    detourKm += barrier.getDetourPenaltyKm().doubleValue();
                    detourMinutes += barrier.getDetourPenaltyMinutes();
                }
            }
        }
        return new BarrierImpact(detourDetected, detourKm, detourMinutes);
    }

    private double applyTrafficCongestion(double baseSpeedKmh, UUID storeId) {
        double adjustedSpeed = baseSpeedKmh;
        if (trafficFeedRepository != null) {
            String zoneKey = storeId != null ? storeId.toString() : "DEFAULT_ZONE";
            Optional<TrafficCongestionFeed> feedOpt = trafficFeedRepository.findByZoneId(zoneKey);
            if (feedOpt.isEmpty() && storeId != null) {
                feedOpt = trafficFeedRepository.findByZoneId("DEFAULT_ZONE");
            }
            if (feedOpt.isPresent()) {
                double congestionFactor = feedOpt.get().getCongestionFactor().doubleValue();
                if (congestionFactor > 1.0) {
                    adjustedSpeed = adjustedSpeed / congestionFactor;
                }
            }
        }
        return Math.max(Math.max(1.0, routingProperties.getEta().getMinSpeedKmh()), adjustedSpeed);
    }

    private WeatherImpact evaluateWeather(UUID storeId) {
        if (weatherRepository == null || storeId == null) {
            return new WeatherImpact(1.0, false, null);
        }
        Optional<WeatherCondition> weatherOpt = weatherRepository.findByStoreId(storeId);
        if (weatherOpt.isEmpty()) {
            return new WeatherImpact(1.0, false, null);
        }
        WeatherCondition condition = weatherOpt.get();
        double rawMultiplier = condition.getSpeedPenaltyMultiplier() != null
            ? condition.getSpeedPenaltyMultiplier().doubleValue()
            : 1.0;
        boolean rainyCondition = isRainyCondition(condition.getConditionType());
        double effectiveMultiplier = Math.max(1.0, rawMultiplier);
        if (rainyCondition && rawMultiplier <= 1.0) {
            effectiveMultiplier = Math.max(1.0, routingProperties.getEta().getMonsoonDefaultMultiplier());
        }
        boolean monsoonDelayActive = condition.isMonsoonDelayBanner() || effectiveMultiplier >= 1.3;
        String delayBanner = monsoonDelayActive
            ? "Monsoon Weather Delay: Two-wheeler speeds reduced due to heavy rain"
            : null;
        return new WeatherImpact(effectiveMultiplier, monsoonDelayActive, delayBanner);
    }

    private boolean isRainyCondition(String conditionType) {
        if (conditionType == null || conditionType.isBlank()) {
            return false;
        }
        String normalized = conditionType.trim().toUpperCase(Locale.ROOT);
        return MONSOON_CONDITION_TYPES.contains(normalized)
            || normalized.contains("RAIN")
            || normalized.contains("MONSOON");
    }

    private InclineImpact evaluateIncline(String vehicleType, double inclinePercent) {
        String vType = vehicleType != null ? vehicleType.toUpperCase(Locale.ROOT) : "MOTORCYCLE";
        if (vType.contains("BICYCLE") || vType.contains("CYCLE")) {
            if (inclinePercent > 0.04) {
                double gradePenalty = 1.0 + (inclinePercent - 0.04) * 12.0;
                String recommendation = inclinePercent >= 0.06 ? "EV_2W" : null;
                return new InclineImpact(gradePenalty, recommendation);
            }
            return new InclineImpact(1.0, null);
        }
        if (inclinePercent > 0.06) {
            return new InclineImpact(1.05, null);
        }
        return new InclineImpact(1.0, null);
    }

    private double computeBreachProbability(double totalMinutes, int slaThresholdMinutes) {
        double slaMinutes = Math.max(1.0, slaThresholdMinutes);
        return 1.0 / (1.0 + Math.exp(-5.0 * (totalMinutes - slaMinutes) / slaMinutes));
    }

    private double haversineDistance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }

    private double adjustedSpeedKmh(Instant instant) {
        var eta = routingProperties.getEta();
        double avgSpeedKmh = eta.getAverageSpeedKmh();
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(eta.getTimezone());
        } catch (Exception ex) {
            zoneId = ZoneId.of("Asia/Kolkata");
        }
        int hour = instant.atZone(zoneId).getHour();
        double adjusted = avgSpeedKmh;
        if (isPeakHour(hour)) {
            adjusted = avgSpeedKmh * eta.getPeakSpeedMultiplier();
        } else if (isNightHour(hour)) {
            adjusted = avgSpeedKmh * eta.getNightSpeedMultiplier();
        }
        return Math.max(Math.max(1.0, eta.getMinSpeedKmh()), adjusted);
    }

    private boolean isPeakHour(int hour) {
        var eta = routingProperties.getEta();
        return (hour >= eta.getMorningPeakStartHour() && hour < eta.getMorningPeakEndHour())
            || (hour >= eta.getEveningPeakStartHour() && hour < eta.getEveningPeakEndHour());
    }

    private boolean isNightHour(int hour) {
        var eta = routingProperties.getEta();
        return hour >= eta.getNightStartHour() || hour < eta.getNightEndHour();
    }

    public String cacheKey(double fromLat, double fromLng, double toLat, double toLng) {
        return String.join(",",
            formatCoordinate(fromLat),
            formatCoordinate(fromLng),
            formatCoordinate(toLat),
            formatCoordinate(toLng));
    }

    private String formatCoordinate(double value) {
        return BigDecimal.valueOf(value)
            .setScale(CACHE_KEY_SCALE, RoundingMode.HALF_UP)
            .toPlainString();
    }

    private record BarrierImpact(boolean detourDetected, double detourKm, int detourMinutes) {}

    private record WeatherImpact(double multiplier, boolean monsoonDelayActive, String delayBanner) {}

    private record InclineImpact(double multiplier, String vehicleRecommendation) {}
}
