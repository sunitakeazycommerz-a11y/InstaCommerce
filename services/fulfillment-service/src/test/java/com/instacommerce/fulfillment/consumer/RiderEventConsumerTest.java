package com.instacommerce.fulfillment.consumer;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.fulfillment.service.DeliveryService;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RiderEventConsumerTest {

    @Mock
    private DeliveryService deliveryService;

    private RiderEventConsumer consumer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        consumer = new RiderEventConsumer(deliveryService, objectMapper);
    }

    @Test
    void consumesRiderAssignedEnvelopeAndSyncsDelivery() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        String json = """
            {
              "eventType": "RiderAssigned",
              "payload": {
                "orderId": "%s",
                "riderId": "%s",
                "storeId": "%s",
                "riderName": "Asha Rider",
                "vehicleType": "MOTORCYCLE",
                "etaMinutes": 12
              }
            }
            """.formatted(orderId, riderId, storeId);

        consumer.onRiderEvent(new ConsumerRecord<>("rider.events", 0, 0, riderId.toString(), json));

        verify(deliveryService).syncRiderAssignment(orderId, riderId, storeId, "Asha Rider", 12);
    }

    @Test
    void consumesSnakeCaseRiderAssignedWithNullOptionalFields() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        String json = """
            {
              "event_type": "RiderAssigned",
              "payload": {
                "order_id": "%s",
                "rider_id": "%s"
              }
            }
            """.formatted(orderId, riderId);

        consumer.onRiderEvent(new ConsumerRecord<>("rider.events", 0, 1, orderId.toString(), json));

        verify(deliveryService).syncRiderAssignment(orderId, riderId, null, null, null);
    }

    @Test
    void ignoresOtherRiderEventTypesAndIncompletePayloads() throws Exception {
        String completedJson = """
            {
              "eventType": "DeliveryCompleted",
              "payload": {
                "orderId": "%s",
                "riderId": "%s"
              }
            }
            """.formatted(UUID.randomUUID(), UUID.randomUUID());

        consumer.onRiderEvent(new ConsumerRecord<>("rider.events", 0, 2, "key", completedJson));

        String invalidJson = """
            {
              "eventType": "RiderAssigned",
              "payload": {
                "orderId": "not-a-uuid"
              }
            }
            """;

        consumer.onRiderEvent(new ConsumerRecord<>("rider.events", 0, 3, "bad-key", invalidJson));

        verifyNoInteractions(deliveryService);
    }
}
