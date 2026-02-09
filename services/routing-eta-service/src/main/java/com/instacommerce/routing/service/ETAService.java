package com.instacommerce.routing.service;

import com.instacommerce.routing.config.RoutingProperties;
import com.instacommerce.routing.dto.response.ETAResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public ETAService(RoutingProperties routingProperties) {
        this.routingProperties = routingProperties;
    }

    /**
     * Calculates ETA using the Haversine formula with road-distance and time-of-day adjustments.
     * Results are cached with a short TTL for high-frequency lookups.
     */
    @Cacheable(value = "eta", key = "#root.target.cacheKey(#fromLat, #fromLng, #toLat, #toLng)")
    public ETAResponse calculateETA(double fromLat, double fromLng, double toLat, double toLng) {
        Instant calculatedAt = Instant.now();
        double straightLineKm = haversineDistance(fromLat, fromLng, toLat, toLng);
        double roadDistanceKm = straightLineKm * routingProperties.getEta().getRoadDistanceMultiplier();
        double avgSpeedKmh = adjustedSpeedKmh(calculatedAt);
        int prepTimeMinutes = routingProperties.getEta().getPreparationTimeMinutes();

        // Travel time in minutes + preparation time
        double travelMinutes = (roadDistanceKm / avgSpeedKmh) * 60.0;
        int estimatedMinutes = (int) Math.ceil(travelMinutes) + prepTimeMinutes;

        log.debug("ETA calculated: {}km, {}min (speed={}km/h, prep={}min)",
            roadDistanceKm, estimatedMinutes, avgSpeedKmh, prepTimeMinutes);

        return new ETAResponse(
            estimatedMinutes,
            BigDecimal.valueOf(roadDistanceKm).setScale(3, RoundingMode.HALF_UP),
            calculatedAt);
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
        int hour = instant.atZone(ZoneId.systemDefault()).getHour();
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

    String cacheKey(double fromLat, double fromLng, double toLat, double toLng) {
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
