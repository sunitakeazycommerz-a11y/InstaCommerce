package com.instacommerce.fulfillment.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.fulfillment.service.PickService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderEventConsumerTest {

    @Mock
    private PickService pickService;

    private OrderEventConsumer consumer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        consumer = new OrderEventConsumer(pickService, objectMapper);
    }

    @Test
    void handlesOrderPlacedWithEmptyPaymentIdWithoutException() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String json = String.format("""
            {
              "eventType": "OrderPlaced",
              "payload": {
                "orderId": "%s",
                "userId": "%s",
                "storeId": "store-1",
                "paymentId": "",
                "items": []
              }
            }
            """, orderId, userId);

        ConsumerRecord<String, String> record = new ConsumerRecord<>("orders.events", 0, 0, orderId.toString(), json);
        consumer.onOrderEvent(record);

        ArgumentCaptor<OrderPlacedPayload> captor = ArgumentCaptor.forClass(OrderPlacedPayload.class);
        verify(pickService).createPickTask(captor.capture());

        OrderPlacedPayload payload = captor.getValue();
        assertThat(payload.orderId()).isEqualTo(orderId);
        assertThat(payload.userId()).isEqualTo(userId);
        assertThat(payload.storeId()).isEqualTo(UUID.nameUUIDFromBytes("store-1".getBytes(StandardCharsets.UTF_8)));
        assertThat(payload.paymentId()).isNull();
    }

    @Test
    void handlesOrderPlacedWithUuidStoreId() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        String json = String.format("""
            {
              "eventType": "OrderPlaced",
              "payload": {
                "orderId": "%s",
                "userId": "%s",
                "storeId": "%s",
                "paymentId": "%s",
                "items": []
              }
            }
            """, orderId, userId, storeId, paymentId);

        ConsumerRecord<String, String> record = new ConsumerRecord<>("orders.events", 0, 0, orderId.toString(), json);
        consumer.onOrderEvent(record);

        ArgumentCaptor<OrderPlacedPayload> captor = ArgumentCaptor.forClass(OrderPlacedPayload.class);
        verify(pickService).createPickTask(captor.capture());

        OrderPlacedPayload payload = captor.getValue();
        assertThat(payload.orderId()).isEqualTo(orderId);
        assertThat(payload.userId()).isEqualTo(userId);
        assertThat(payload.storeId()).isEqualTo(storeId);
        assertThat(payload.paymentId()).isEqualTo(paymentId);
    }

    @Test
    void handlesOrderCancelledWithSnakeCaseOrderId() throws Exception {
        UUID orderId = UUID.randomUUID();
        String json = String.format("""
            {
              "eventType": "OrderCancelled",
              "payload": {
                "order_id": "%s",
                "reason": "customer-request"
              }
            }
            """, orderId);

        ConsumerRecord<String, String> record = new ConsumerRecord<>("orders.events", 0, 0, orderId.toString(), json);
        consumer.onOrderEvent(record);

        verify(pickService).cancelPickTask(orderId, "customer-request");
    }

    @Test
    void handlesOrderCancelledWithKeyFallback() throws Exception {
        UUID orderId = UUID.randomUUID();
        String json = """
            {
              "eventType": "OrderCancelled",
              "payload": {
                "reason": "payment-failed"
              }
            }
            """;

        ConsumerRecord<String, String> record = new ConsumerRecord<>("orders.events", 0, 0, orderId.toString(), json);
        consumer.onOrderEvent(record);

        verify(pickService).cancelPickTask(orderId, "payment-failed");
    }

    @Test
    void handlesOrderPlacedWithDropoffCoordinatesAndOmitsWhenMissing() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        String json = String.format("""
            {
              "eventType": "OrderPlaced",
              "payload": {
                "orderId": "%s",
                "userId": "%s",
                "storeId": "%s",
                "dropoffLat": 12.9352,
                "dropoffLng": "77.6245",
                "items": []
              }
            }
            """, orderId, userId, storeId);

        consumer.onOrderEvent(new ConsumerRecord<>("orders.events", 0, 0, orderId.toString(), json));

        ArgumentCaptor<OrderPlacedPayload> captor = ArgumentCaptor.forClass(OrderPlacedPayload.class);
        verify(pickService).createPickTask(captor.capture());
        assertThat(captor.getValue().dropoffLat()).isEqualByComparingTo(new BigDecimal("12.9352"));
        assertThat(captor.getValue().dropoffLng()).isEqualByComparingTo(new BigDecimal("77.6245"));
    }
}
