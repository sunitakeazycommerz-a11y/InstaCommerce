package com.instacommerce.routing.service;

import com.instacommerce.routing.config.RoutingProperties;
import com.instacommerce.routing.dto.response.ETAResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class ETAService {

    private static final Logger log = LoggerFactory.getLogger(ETAService.class);
    private static final double EARTH_RADIUS_KM = 6371.0;

    private final RoutingProperties routingProperties;

    public ETAService(RoutingProperties routingProperties) {
        this.routingProperties = routingProperties;
    }

    /**
     * Calculates ETA using the Haversine formula with configurable average speed as fallback.
     * Results are cached with a short TTL for high-frequency lookups.
     */
    @Cacheable(value = "eta", key = "#fromLat + ',' + #fromLng + ',' + #toLat + ',' + #toLng")
    public ETAResponse calculateETA(double fromLat, double fromLng, double toLat, double toLng) {
        double distanceKm = haversineDistance(fromLat, fromLng, toLat, toLng);
        int avgSpeedKmh = routingProperties.getEta().getAverageSpeedKmh();
        int prepTimeMinutes = routingProperties.getEta().getPreparationTimeMinutes();

        // Travel time in minutes + preparation time
        double travelMinutes = (distanceKm / avgSpeedKmh) * 60.0;
        int estimatedMinutes = (int) Math.ceil(travelMinutes) + prepTimeMinutes;

        log.debug("ETA calculated: {}km, {}min (speed={}km/h, prep={}min)",
            distanceKm, estimatedMinutes, avgSpeedKmh, prepTimeMinutes);

        return new ETAResponse(
            estimatedMinutes,
            BigDecimal.valueOf(distanceKm).setScale(3, RoundingMode.HALF_UP),
            Instant.now());
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
}
