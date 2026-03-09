package com.instacommerce.order.consumer;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.order.domain.model.OrderStatus;
import com.instacommerce.order.service.OrderService;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FulfillmentEventConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private OrderService orderService;

    @Test
    void mapsDispatchedEventToOutForDelivery() throws Exception {
        UUID orderId = UUID.randomUUID();
        FulfillmentEventConsumer consumer = new FulfillmentEventConsumer(objectMapper, orderService);

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
            "fulfillment.events",
            0,
            0L,
            orderId.toString(),
            """
                {"eventType":"OrderDispatched","aggregateId":"%s","payload":{"orderId":"%s"}}
                """.formatted(orderId, orderId));

        consumer.onFulfillmentEvent(record);

        verify(orderService).advanceLifecycleFromFulfillment(
            orderId,
            OrderStatus.OUT_FOR_DELIVERY,
            "system:fulfillment-event",
            "order-dispatched");
    }

    @Test
    void ignoresUnsupportedFulfillmentEvent() throws Exception {
        UUID orderId = UUID.randomUUID();
        FulfillmentEventConsumer consumer = new FulfillmentEventConsumer(objectMapper, orderService);

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
            "fulfillment.events",
            0,
            0L,
            orderId.toString(),
            """
                {"eventType":"OrderModified","aggregateId":"%s","payload":{"orderId":"%s"}}
                """.formatted(orderId, orderId));

        consumer.onFulfillmentEvent(record);

        verifyNoInteractions(orderService);
    }
}
