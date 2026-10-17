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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
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
        assertThat(payload.paymentId()).isNull();
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
}
