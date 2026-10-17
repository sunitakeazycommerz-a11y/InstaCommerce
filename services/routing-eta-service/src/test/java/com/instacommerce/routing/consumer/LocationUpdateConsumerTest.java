package com.instacommerce.routing.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.routing.config.RoutingProperties;
import com.instacommerce.routing.dto.request.LocationUpdateRequest;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

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
}
