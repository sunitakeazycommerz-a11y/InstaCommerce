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
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class ETAService {

    private static final Logger log = LoggerFactory.getLogger(ETAService.class);
    private static final double EARTH_RADIUS_KM = 6371.0;
    private static final int CACHE_KEY_SCALE = 4;
    private static final int MORNING_PEAK_START = 7;
    private static final int MORNING_PEAK_END = 10;
    private static final int EVENING_PEAK_START = 17;
    private static final int EVENING_PEAK_END = 20;
    private static final int NIGHT_START = 22;
    private static final int NIGHT_END = 5;

    private final RoutingProperties routingProperties;
    private final RoutingBarrierRepository barrierRepository;
    private final WeatherConditionRepository weatherRepository;
    private final TrafficCongestionFeedRepository trafficFeedRepository;

    public ETAService(RoutingProperties routingProperties) {
        this(routingProperties, (RoutingBarrierRepository) null, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ETAService(RoutingProperties routingProperties,
                      ObjectProvider<RoutingBarrierRepository> barrierRepositoryProvider,
                      ObjectProvider<WeatherConditionRepository> weatherRepositoryProvider,
                      ObjectProvider<TrafficCongestionFeedRepository> trafficFeedRepositoryProvider) {
        this.routingProperties = routingProperties;
        this.barrierRepository = barrierRepositoryProvider != null ? barrierRepositoryProvider.getIfAvailable() : null;
        this.weatherRepository = weatherRepositoryProvider != null ? weatherRepositoryProvider.getIfAvailable() : null;
        this.trafficFeedRepository = trafficFeedRepositoryProvider != null ? trafficFeedRepositoryProvider.getIfAvailable() : null;
    }

    public ETAService(RoutingProperties routingProperties,
                      RoutingBarrierRepository barrierRepository,
                      WeatherConditionRepository weatherRepository,
                      TrafficCongestionFeedRepository trafficFeedRepository) {
        this.routingProperties = routingProperties;
        this.barrierRepository = barrierRepository;
        this.weatherRepository = weatherRepository;
        this.trafficFeedRepository = trafficFeedRepository;
    }

    /**
     * Calculates ETA using the Haversine formula with road-distance and time-of-day adjustments.
     * Results are cached with a short TTL for high-frequency lookups.
     */
    @Cacheable(value = "eta", key = "#root.target.cacheKey(#fromLat, #fromLng, #toLat, #toLng)")
    public ETAResponse calculateETA(double fromLat, double fromLng, double toLat, double toLng) {
        ETAEstimate estimate = calculateETAWithConfidence(fromLat, fromLng, toLat, toLng, null, "MOTORCYCLE", 0.0);
        return new ETAResponse(
            estimate.etaMinutes(),
            estimate.distanceKm(),
            estimate.calculatedAt());
    }

    public ETAEstimate calculateETAWithConfidence(double fromLat, double fromLng,
                                                  double toLat, double toLng) {
        return calculateETAWithConfidence(fromLat, fromLng, toLat, toLng, null, "MOTORCYCLE", 0.0);
    }

    /**
     * Calculates ETA with confidence bounds, integrating:
     * 1. Road barrier closures & detours (#616)
     * 2. Live municipal traffic congestion feeds (#602)
     * 3. Monsoon & rainy weather coefficient multipliers (#698)
     * 4. Terrain slope and bicycle incline penalties (#610)
     */
    public ETAEstimate calculateETAWithConfidence(double fromLat, double fromLng,
                                                  double toLat, double toLng,
                                                  UUID storeId, String vehicleType,
                                                  double inclinePercent) {
        Instant calculatedAt = Instant.now();
        double straightLineKm = haversineDistance(fromLat, fromLng, toLat, toLng);
        double roadDistanceKm = straightLineKm * routingProperties.getEta().getRoadDistanceMultiplier();

        // 1. Dynamic road barrier inspection & detour routing (#616)
        boolean detourDetected = false;
        double barrierDetourKm = 0.0;
        int barrierDetourMinutes = 0;
        if (barrierRepository != null) {
            List<RoutingBarrier> activeBarriers = barrierRepository.findByIsActiveTrue();
            for (RoutingBarrier barrier : activeBarriers) {
                if (barrier.intersects(fromLat, fromLng, toLat, toLng)) {
                    detourDetected = true;
                    barrierDetourKm += barrier.getDetourPenaltyKm().doubleValue();
                    barrierDetourMinutes += barrier.getDetourPenaltyMinutes();
                }
            }
        }
        roadDistanceKm += barrierDetourKm;

        // 2. Base speed adjusted for time of day
        double avgSpeedKmh = adjustedSpeedKmh(calculatedAt);

        // 3. Live municipal traffic feed speed adjustment (#602)
        if (trafficFeedRepository != null) {
            String zoneKey = storeId != null ? storeId.toString() : "DEFAULT_ZONE";
            var feedOpt = trafficFeedRepository.findByZoneId(zoneKey);
            if (feedOpt.isEmpty() && storeId != null) {
                feedOpt = trafficFeedRepository.findByZoneId("DEFAULT_ZONE");
            }
            if (feedOpt.isPresent()) {
                double congestionFactor = feedOpt.get().getCongestionFactor().doubleValue();
                if (congestionFactor > 0.1) {
                    avgSpeedKmh = avgSpeedKmh / congestionFactor;
                }
            }
        }

        // 4. Rainy weather / Monsoon season speed multiplier (#698)
        boolean monsoonDelayActive = false;
        String delayBanner = null;
        double weatherMultiplier = 1.0;
        if (weatherRepository != null && storeId != null) {
            var weatherOpt = weatherRepository.findByStoreId(storeId);
            if (weatherOpt.isPresent()) {
                WeatherCondition condition = weatherOpt.get();
                weatherMultiplier = condition.getSpeedPenaltyMultiplier().doubleValue();
                if (condition.isMonsoonDelayBanner() || weatherMultiplier >= 1.3) {
                    monsoonDelayActive = true;
                    delayBanner = "Monsoon Weather Delay: Two-wheeler speeds reduced due to heavy rain";
                }
            }
        }

        double travelMinutes = (roadDistanceKm / avgSpeedKmh) * 60.0 * weatherMultiplier;

        // 5. Elevation & steep incline penalty for bicycles (#610)
        String vehicleRecommendation = null;
        String vType = vehicleType != null ? vehicleType.toUpperCase() : "MOTORCYCLE";
        if (vType.contains("BICYCLE") || vType.contains("CYCLE")) {
            if (inclinePercent > 0.04) { // Exceeds 4% incline
                double gradePenalty = 1.0 + (inclinePercent - 0.04) * 12.0;
                travelMinutes *= gradePenalty;
                if (inclinePercent >= 0.06) {
                    vehicleRecommendation = "EV_2W"; // Prioritize electric 2-wheelers over pedal bicycles
                }
            }
        } else if (inclinePercent > 0.06) {
            travelMinutes *= 1.05;
        }

        int prepTimeMinutes = routingProperties.getEta().getPreparationTimeMinutes();
        int etaMinutes = (int) Math.ceil(travelMinutes) + prepTimeMinutes + barrierDetourMinutes;

        var breach = routingProperties.getBreach();
        int etaLow = Math.max(1, (int) Math.round(etaMinutes * breach.getEtaLowMultiplier()));
        int etaHigh = (int) Math.round(etaMinutes * breach.getEtaHighMultiplier());

        double slaMinutes = breach.getSlaThresholdMinutes();
        double breachProbability = 1.0 / (1.0 + Math.exp(-5.0 * (etaMinutes - slaMinutes) / slaMinutes));

        return new ETAEstimate(
            etaMinutes, etaLow, etaHigh,
            BigDecimal.valueOf(roadDistanceKm).setScale(3, RoundingMode.HALF_UP),
            breachProbability, calculatedAt,
            detourDetected, monsoonDelayActive, delayBanner, vehicleRecommendation);
    }

    /**
     * Haversine formula to calculate great-circle distance between two points on Earth.
     */
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
        double avgSpeedKmh = routingProperties.getEta().getAverageSpeedKmh();
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(routingProperties.getEta().getTimezone());
        } catch (Exception ex) {
            zoneId = ZoneId.of("Asia/Kolkata");
        }
        int hour = instant.atZone(zoneId).getHour();
        double adjusted = avgSpeedKmh;
        if (isPeakHour(hour)) {
            adjusted = avgSpeedKmh * routingProperties.getEta().getPeakSpeedMultiplier();
        } else if (isNightHour(hour)) {
            adjusted = avgSpeedKmh * routingProperties.getEta().getNightSpeedMultiplier();
        }
        return Math.max(5.0, adjusted);
    }

    private boolean isPeakHour(int hour) {
        return (hour >= MORNING_PEAK_START && hour < MORNING_PEAK_END)
            || (hour >= EVENING_PEAK_START && hour < EVENING_PEAK_END);
    }

    private boolean isNightHour(int hour) {
        return hour >= NIGHT_START || hour < NIGHT_END;
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
}
