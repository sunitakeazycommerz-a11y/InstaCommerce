package com.instacommerce.notification.consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.instacommerce.contracts.events.EventEnvelope;
import com.instacommerce.notification.service.NotificationService;
import com.instacommerce.notification.service.PreferenceLookupException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderEventConsumerTest {

    @Mock
    private NotificationService notificationService;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("onOrderEvent rethrows PreferenceLookupException for Kafka retry/DLQ handling")
    void rethrowsPreferenceLookupException() throws Exception {
        OrderEventConsumer consumer = new OrderEventConsumer(notificationService, objectMapper);
        EventEnvelope envelope = new EventEnvelope(
            UUID.randomUUID().toString(),
            "OrderPlaced",
            "order",
            "ord-1",
            Instant.now(),
            "1",
            "order-service",
            UUID.randomUUID().toString(),
            objectMapper.valueToTree(Map.of("userId", UUID.randomUUID().toString()))
        );
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
            "orders.events", 0, 10L, "ord-1", objectMapper.writeValueAsString(envelope)
        );
        doThrow(new PreferenceLookupException("identity timeout"))
            .when(notificationService).handleEvent(eq(record), any(EventEnvelope.class));

        assertThatThrownBy(() -> consumer.onOrderEvent(record))
            .isInstanceOf(PreferenceLookupException.class);
    }
}
