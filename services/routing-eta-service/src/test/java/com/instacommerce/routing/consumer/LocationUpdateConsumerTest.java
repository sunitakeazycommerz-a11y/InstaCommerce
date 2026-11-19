package com.instacommerce.routing.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.routing.config.RoutingProperties;
import com.instacommerce.routing.domain.model.Delivery;
import com.instacommerce.routing.domain.model.DeliveryStatus;
import com.instacommerce.routing.dto.request.LocationUpdateRequest;
import com.instacommerce.routing.dto.response.ETAEstimate;
import com.instacommerce.routing.repository.DeliveryRepository;
import com.instacommerce.routing.service.DeliveryService;
import com.instacommerce.routing.service.ETAService;
import com.instacommerce.routing.service.TrackingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocationUpdateConsumerTest {

    @Mock private TrackingService trackingService;
    @Mock private DeliveryRepository deliveryRepository;
    @Mock private ETAService etaService;
    @Mock private DeliveryService deliveryService;

    private LocationUpdateConsumer consumer;

    @BeforeEach
    void setUp() {
        RoutingProperties properties = new RoutingProperties();
        consumer = new LocationUpdateConsumer(
            trackingService,
            new ObjectMapper(),
            deliveryRepository,
            etaService,
            deliveryService,
            properties
        );
    }

    @Test
    void parsesGoLocationTelemetryWithSpeedKmhAndHeadingDegrees() {
        UUID deliveryId = UUID.randomUUID();
        String json = String.format("""
            {
              "delivery_id": "%s",
              "lat": 12.9716,
              "lng": 77.5946,
              "speed_kmh": 32.5,
              "heading_degrees": 180.0
            }
            """, deliveryId);

        consumer.handleLocationUpdate(json);

        ArgumentCaptor<LocationUpdateRequest> captor = ArgumentCaptor.forClass(LocationUpdateRequest.class);
        verify(trackingService).recordLocation(captor.capture());

        LocationUpdateRequest request = captor.getValue();
        assertThat(request.deliveryId()).isEqualTo(deliveryId);
        assertThat(request.latitude()).isEqualByComparingTo(new BigDecimal("12.9716"));
        assertThat(request.longitude()).isEqualByComparingTo(new BigDecimal("77.5946"));
        assertThat(request.speedKmh()).isEqualByComparingTo(new BigDecimal("32.5"));
        assertThat(request.heading()).isEqualByComparingTo(new BigDecimal("180.0"));
    }

    @Test
    void triggersSlaBreachUpdateEvenWhenEtaDeltaIsBelowMinDelta() {
        UUID deliveryId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Delivery delivery = new Delivery();
        delivery.setId(deliveryId);
        delivery.setOrderId(UUID.randomUUID());
        delivery.setStoreId(storeId);
        delivery.setStatus(DeliveryStatus.EN_ROUTE);
        delivery.setEstimatedMinutes(31);
        delivery.setDropoffLat(new BigDecimal("12.9500"));
        delivery.setDropoffLng(new BigDecimal("77.6000"));
        delivery.setVehicleType("MOTORCYCLE");
        delivery.setCompensationTriggered(false);

        ETAEstimate breachEstimate = new ETAEstimate(
            32, 26, 42, new BigDecimal("4.000"), 0.85, Instant.now(),
            false, false, null, null, 1, 3);

        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(deliveryService.countPrecedingStops(delivery, 12.9300, 77.5900)).thenReturn(1);
        when(etaService.calculateETAWithConfidence(12.9300, 77.5900, 12.9500, 77.6000, storeId, "MOTORCYCLE", 0.0, 1))
            .thenReturn(breachEstimate);

        String json = String.format("""
            {
              "delivery_id": "%s",
              "lat": 12.9300,
              "lng": 77.5900
            }
            """, deliveryId);

        consumer.handleLocationUpdate(json);

        verify(deliveryService).updateETA(deliveryId, breachEstimate);
    }

    @Test
    void skipsMalformedCoordinatesWithoutThrowingToDlt() {
        UUID deliveryId = UUID.randomUUID();
        String json = String.format("""
            {
              "delivery_id": "%s",
              "lat": "not-a-number",
              "lng": 77.5946
            }
            """, deliveryId);

        consumer.handleLocationUpdate(json);

        verify(trackingService, never()).recordLocation(any());
        verify(deliveryService, never()).updateETA(eq(deliveryId), any());
    }
}
