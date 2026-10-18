package com.instacommerce.routing.service;

import com.instacommerce.routing.config.RoutingProperties;
import com.instacommerce.routing.domain.model.RoutingBarrier;
import com.instacommerce.routing.domain.model.TrafficCongestionFeed;
import com.instacommerce.routing.domain.model.WeatherCondition;
import com.instacommerce.routing.dto.response.ETAEstimate;
import com.instacommerce.routing.repository.RoutingBarrierRepository;
import com.instacommerce.routing.repository.TrafficCongestionFeedRepository;
import com.instacommerce.routing.repository.WeatherConditionRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ETAServiceTest {

    @Mock
    private RoutingBarrierRepository barrierRepository;

    @Mock
    private WeatherConditionRepository weatherRepository;

    @Mock
    private TrafficCongestionFeedRepository trafficFeedRepository;

    private RoutingProperties routingProperties;
    private ETAService etaService;

    @BeforeEach
    void setUp() {
        routingProperties = new RoutingProperties();
        routingProperties.getEta().setAverageSpeedKmh(25);
        routingProperties.getEta().setPreparationTimeMinutes(3);
        routingProperties.getEta().setRoadDistanceMultiplier(1.4);
        routingProperties.getEta().setPeakSpeedMultiplier(0.6);
        routingProperties.getEta().setNightSpeedMultiplier(1.2);
        routingProperties.getBreach().setSlaThresholdMinutes(30);
        routingProperties.getBreach().setBreachAlertThreshold(0.7);

        etaService = new ETAService(
            routingProperties,
            barrierRepository,
            weatherRepository,
            trafficFeedRepository
        );
    }

    @Test
    @DisplayName("Dynamic barrier detour adds detour distance and minutes (Issue #616)")
    void shouldApplyDetourAroundActiveBarriers() {
        double fromLat = 12.9000;
        double fromLng = 77.5000;
        double toLat = 12.9500;
        double toLng = 77.5500;

        RoutingBarrier barrier = new RoutingBarrier();
        barrier.setName("Koramangala Bridge Maintenance Closure");
        barrier.setMinLat(new BigDecimal("12.9100"));
        barrier.setMinLng(new BigDecimal("77.5100"));
        barrier.setMaxLat(new BigDecimal("12.9400"));
        barrier.setMaxLng(new BigDecimal("77.5400"));
        barrier.setDetourPenaltyKm(new BigDecimal("3.50"));
        barrier.setDetourPenaltyMinutes(12);
        barrier.setActive(true);

        when(barrierRepository.findByIsActiveTrue()).thenReturn(List.of(barrier));

        ETAEstimate estimate = etaService.calculateETAWithConfidence(
            fromLat, fromLng, toLat, toLng, null, "MOTORCYCLE", 0.0);

        assertTrue(estimate.detourDetected());
        assertTrue(estimate.etaMinutes() >= 15);
    }

    @Test
    @DisplayName("Monsoon rainy weather multiplier adds travel delay and displays banner (Issue #698)")
    void shouldApplyMonsoonRainPenaltyMultiplier() {
        UUID storeId = UUID.randomUUID();
        double fromLat = 12.9000;
        double fromLng = 77.5000;
        double toLat = 12.9200;
        double toLng = 77.5200;

        when(barrierRepository.findByIsActiveTrue()).thenReturn(List.of());

        WeatherCondition monsoon = new WeatherCondition();
        monsoon.setStoreId(storeId);
        monsoon.setConditionType("HEAVY_RAIN");
        monsoon.setSpeedPenaltyMultiplier(new BigDecimal("1.80"));
        monsoon.setMonsoonDelayBanner(true);

        when(weatherRepository.findByStoreId(storeId)).thenReturn(Optional.of(monsoon));

        ETAEstimate estimate = etaService.calculateETAWithConfidence(
            fromLat, fromLng, toLat, toLng, storeId, "MOTORCYCLE", 0.0);

        assertTrue(estimate.monsoonDelayActive());
        assertNotNull(estimate.delayBanner());
        assertTrue(estimate.delayBanner().contains("Monsoon Weather Delay"));
    }

    @Test
    @DisplayName("Municipal live traffic feed slows speed and increases ETA (Issue #602)")
    void shouldApplyTrafficCongestionFactor() {
        UUID storeId = UUID.randomUUID();
        double fromLat = 12.9000;
        double fromLng = 77.5000;
        double toLat = 12.9200;
        double toLng = 77.5200;

        when(barrierRepository.findByIsActiveTrue()).thenReturn(List.of());

        TrafficCongestionFeed feed = new TrafficCongestionFeed();
        feed.setZoneId(storeId.toString());
        feed.setCongestionFactor(new BigDecimal("2.00")); // 2x congestion reduces speed by half

        when(trafficFeedRepository.findByZoneId("DEFAULT_ZONE")).thenReturn(Optional.empty());
        when(trafficFeedRepository.findByZoneId(storeId.toString())).thenReturn(Optional.of(feed));

        ETAEstimate normalEstimate = etaService.calculateETAWithConfidence(
            fromLat, fromLng, toLat, toLng, null, "MOTORCYCLE", 0.0);

        ETAEstimate trafficEstimate = etaService.calculateETAWithConfidence(
            fromLat, fromLng, toLat, toLng, storeId, "MOTORCYCLE", 0.0);

        assertTrue(trafficEstimate.etaMinutes() > normalEstimate.etaMinutes());
    }

    @Test
    @DisplayName("Elevation and steep incline penalty for bicycles recommends EV_2W (Issue #610)")
    void shouldApplyBicycleInclinePenaltyAndRecommendEV() {
        double fromLat = 12.9000;
        double fromLng = 77.5000;
        double toLat = 12.9200;
        double toLng = 77.5200;

        when(barrierRepository.findByIsActiveTrue()).thenReturn(List.of());

        // Steep 8% incline on pedal bicycle
        ETAEstimate estimate = etaService.calculateETAWithConfidence(
            fromLat, fromLng, toLat, toLng, null, "BICYCLE", 0.08);

        assertEquals("EV_2W", estimate.vehicleRecommendation());
    }
}
