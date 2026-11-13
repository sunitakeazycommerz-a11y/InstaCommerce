package com.instacommerce.routing.consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.routing.service.DeliveryService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RiderEventConsumerTest {

    @Mock
    private DeliveryService deliveryService;

    private RiderEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new RiderEventConsumer(deliveryService, new ObjectMapper());
    }

    @Test
    void handlesTopLevelRiderAssignedEvent() {
        UUID orderId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        String message = """
            {
              "eventType": "RiderAssigned",
              "orderId": "%s",
              "riderId": "%s",
              "storeId": "%s",
              "pickupLat": 12.9716,
              "pickupLng": 77.5946,
              "dropoffLat": 12.9352,
              "dropoffLng": 77.6245
            }
            """.formatted(orderId, riderId, storeId);

        assertDoesNotThrow(() -> consumer.handleRiderEvent(message));

        verify(deliveryService).createDelivery(
            eq(orderId),
            eq(riderId),
            eq(storeId),
            eq(new BigDecimal("12.9716")),
            eq(new BigDecimal("77.5946")),
            eq(new BigDecimal("12.9352")),
            eq(new BigDecimal("77.6245")));
    }

    @Test
    void handlesEventEnvelopeWrappedRiderAssignedEvent() {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        String message = """
            {
              "id": "%s",
              "eventType": "RiderAssigned",
              "aggregateType": "RiderAssignment",
              "aggregateId": "%s",
              "schemaVersion": "v1",
              "sourceService": "rider-fleet-service",
              "payload": {
                "orderId": "%s",
                "riderId": "%s",
                "storeId": "%s",
                "riderName": "Asha",
                "pickupLat": 12.9716,
                "pickupLng": 77.5946,
                "dropoffLat": 12.9352,
                "dropoffLng": 77.6245
              }
            }
            """.formatted(eventId, orderId, orderId, riderId, storeId);

        assertDoesNotThrow(() -> consumer.handleRiderEvent(message));

        verify(deliveryService).createDelivery(
            eq(orderId),
            eq(riderId),
            eq(storeId),
            eq(new BigDecimal("12.9716")),
            eq(new BigDecimal("77.5946")),
            eq(new BigDecimal("12.9352")),
            eq(new BigDecimal("77.6245")));
    }

    @Test
    void handlesSnakeCaseEnvelopeAndPayloadFields() {
        UUID orderId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        String message = """
            {
              "event_type": "RiderAssigned",
              "payload": {
                "order_id": "%s",
                "rider_id": "%s",
                "store_id": "%s",
                "pickup_lat": "12.9716",
                "pickup_lng": "77.5946",
                "dropoff_lat": "12.9352",
                "dropoff_lng": "77.6245"
              }
            }
            """.formatted(orderId, riderId, storeId);

        assertDoesNotThrow(() -> consumer.handleRiderEvent(message));

        verify(deliveryService).createDelivery(
            eq(orderId),
            eq(riderId),
            eq(storeId),
            eq(new BigDecimal("12.9716")),
            eq(new BigDecimal("77.5946")),
            eq(new BigDecimal("12.9352")),
            eq(new BigDecimal("77.6245")));
    }

    @Test
    void allowsMissingOrInvalidOptionalStoreId() {
        UUID orderId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        String message = """
            {
              "eventType": "RiderAssigned",
              "payload": {
                "orderId": "%s",
                "riderId": "%s",
                "storeId": "",
                "pickupLat": 12.9716,
                "pickupLng": 77.5946,
                "dropoffLat": 12.9352,
                "dropoffLng": 77.6245
              }
            }
            """.formatted(orderId, riderId);

        assertDoesNotThrow(() -> consumer.handleRiderEvent(message));

        verify(deliveryService).createDelivery(
            eq(orderId),
            eq(riderId),
            isNull(),
            eq(new BigDecimal("12.9716")),
            eq(new BigDecimal("77.5946")),
            eq(new BigDecimal("12.9352")),
            eq(new BigDecimal("77.6245")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        """
        {
          "eventType": "RiderAssigned",
          "payload": {
            "orderId": "11111111-1111-1111-1111-111111111111",
            "riderId": "22222222-2222-2222-2222-222222222222",
            "pickupLat": 12.9716,
            "pickupLng": 77.5946
          }
        }
        """,
        """
        {
          "eventType": "RiderAssigned",
          "orderId": "11111111-1111-1111-1111-111111111111",
          "riderId": "22222222-2222-2222-2222-222222222222",
          "pickupLat": null,
          "pickupLng": 77.5946,
          "dropoffLat": 12.9352,
          "dropoffLng": 77.6245
        }
        """,
        """
        {
          "eventType": "RiderAssigned",
          "orderId": "11111111-1111-1111-1111-111111111111",
          "riderId": "22222222-2222-2222-2222-222222222222",
          "pickupLat": "",
          "pickupLng": 77.5946,
          "dropoffLat": "12.9352",
          "dropoffLng": "invalid"
        }
        """,
        """
        {
          "eventType": "RiderAssigned",
          "payload": {
            "orderId": "not-a-uuid",
            "riderId": "22222222-2222-2222-2222-222222222222",
            "pickupLat": 12.9716,
            "pickupLng": 77.5946,
            "dropoffLat": 12.9352,
            "dropoffLng": 77.6245
          }
        }
        """,
        """
        {
          "eventType": "RiderAssigned",
          "payload": {
            "orderId": "11111111-1111-1111-1111-111111111111",
            "pickupLat": 12.9716,
            "pickupLng": 77.5946,
            "dropoffLat": 12.9352,
            "dropoffLng": 77.6245
          }
        }
        """
    })
    void skipsIncompleteOrMalformedRiderAssignedWithoutCrashing(String message) {
        assertDoesNotThrow(() -> consumer.handleRiderEvent(message));
        verifyNoInteractions(deliveryService);
    }

    @Test
    void ignoresNonRiderAssignedEvents() {
        String message = """
            {
              "eventType": "RiderOffline",
              "payload": {
                "riderId": "%s"
              }
            }
            """.formatted(UUID.randomUUID());

        assertDoesNotThrow(() -> consumer.handleRiderEvent(message));
        verifyNoInteractions(deliveryService);
    }
}
