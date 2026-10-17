package com.instacommerce.riderfleet.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.riderfleet.dto.request.AssignRiderRequest;
import com.instacommerce.riderfleet.service.RiderAssignmentService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class FulfillmentEventConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private RiderAssignmentService riderAssignmentService;

    private FulfillmentEventConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        consumer = new FulfillmentEventConsumer(riderAssignmentService, objectMapper);
    }

    @Test
    @DisplayName("Successfully processes OrderPacked event with string storeId")
    void handlesOrderPackedWithStringStoreId() throws Exception {
        UUID orderId = UUID.randomUUID();
        Map<String, Object> payload = Map.of(
            "orderId", orderId.toString(),
            "storeId", "darkstore-blr-01",
            "pickupLat", "12.9716",
            "pickupLng", "77.5946"
        );
        Map<String, Object> envelope = Map.of(
            "id", UUID.randomUUID().toString(),
            "eventType", "OrderPacked",
            "aggregateId", orderId.toString(),
            "aggregateType", "Fulfillment",
            "eventTime", Instant.now().toString(),
            "payload", payload
        );

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
            "fulfillment.events", 0, 0, orderId.toString(), objectMapper.writeValueAsString(envelope)
        );

        consumer.onFulfillmentEvent(record);

        verify(riderAssignmentService).assignRider(
            eq(orderId),
            any(UUID.class),
            eq(new BigDecimal("12.9716")),
            eq(new BigDecimal("77.5946"))
        );
    }

    @Test
    @DisplayName("Successfully processes OrderPacked event with UUID storeId")
    void handlesOrderPackedWithUuidStoreId() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Map<String, Object> payload = Map.of(
            "orderId", orderId.toString(),
            "storeId", storeId.toString(),
            "pickupLat", "12.9716",
            "pickupLng", "77.5946"
        );
        Map<String, Object> envelope = Map.of(
            "id", UUID.randomUUID().toString(),
            "eventType", "OrderPacked",
            "aggregateId", orderId.toString(),
            "aggregateType", "Fulfillment",
            "eventTime", Instant.now().toString(),
            "payload", payload
        );

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
            "fulfillment.events", 0, 0, orderId.toString(), objectMapper.writeValueAsString(envelope)
        );

        consumer.onFulfillmentEvent(record);

        verify(riderAssignmentService).assignRider(
            eq(orderId),
            eq(storeId),
            eq(new BigDecimal("12.9716")),
            eq(new BigDecimal("77.5946"))
        );
    }

    @Test
    @DisplayName("Skips assignment when pickup coordinates are missing")
    void skipsAssignmentWhenCoordinatesMissing() throws Exception {
        UUID orderId = UUID.randomUUID();
        Map<String, Object> payload = Map.of(
            "orderId", orderId.toString(),
            "storeId", "darkstore-blr-01"
        );
        Map<String, Object> envelope = Map.of(
            "id", UUID.randomUUID().toString(),
            "eventType", "OrderPacked",
            "aggregateId", orderId.toString(),
            "aggregateType", "Fulfillment",
            "eventTime", Instant.now().toString(),
            "payload", payload
        );

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
            "fulfillment.events", 0, 0, orderId.toString(), objectMapper.writeValueAsString(envelope)
        );

        consumer.onFulfillmentEvent(record);

        verify(riderAssignmentService, never()).assignRider(any(), any(), any(), any());
    }

    @Test
    @DisplayName("AssignRiderRequest deserializes string storeId cleanly")
    void assignRiderRequestParsesStringStoreId() throws Exception {
        UUID orderId = UUID.randomUUID();
        String json = """
            {
              "orderId": "%s",
              "storeId": "darkstore-blr-01",
              "pickupLat": 12.9716,
              "pickupLng": 77.5946
            }
            """.formatted(orderId);

        AssignRiderRequest request = objectMapper.readValue(json, AssignRiderRequest.class);
        assertThat(request.orderId()).isEqualTo(orderId);
        assertThat(request.storeId()).isNotNull();
        assertThat(request.pickupLat()).isEqualTo(new BigDecimal("12.9716"));
        assertThat(request.pickupLng()).isEqualTo(new BigDecimal("77.5946"));
    }
}
