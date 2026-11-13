package com.instacommerce.notification.consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

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
class NotificationEventConsumerTest {

    @Mock
    private NotificationService notificationService;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("onOrderEvent rethrows PreferenceLookupException for Kafka retry/DLQ handling")
    void rethrowsPreferenceLookupException() throws Exception {
        NotificationEventConsumer consumer = new NotificationEventConsumer(notificationService, objectMapper);
        ConsumerRecord<String, String> record = eventRecord("orders.events", "OrderPlaced", "order-service");
        doThrow(new PreferenceLookupException("identity timeout"))
            .when(notificationService).handleEvent(eq(record), any(EventEnvelope.class));

        assertThatThrownBy(() -> consumer.onOrderEvent(record))
            .isInstanceOf(PreferenceLookupException.class);
    }

    @Test
    @DisplayName("onPaymentEvent and onFulfillmentEvent delegate to NotificationService")
    void delegatesPaymentAndFulfillmentEvents() throws Exception {
        NotificationEventConsumer consumer = new NotificationEventConsumer(notificationService, objectMapper);
        ConsumerRecord<String, String> paymentRecord = eventRecord("payments.events", "PaymentRefunded", "payment-service");
        ConsumerRecord<String, String> fulfillmentRecord = eventRecord("fulfillment.events", "OrderDispatched", "fulfillment-service");

        consumer.onPaymentEvent(paymentRecord);
        consumer.onFulfillmentEvent(fulfillmentRecord);

        verify(notificationService).handleEvent(eq(paymentRecord), any(EventEnvelope.class));
        verify(notificationService).handleEvent(eq(fulfillmentRecord), any(EventEnvelope.class));
    }

    private ConsumerRecord<String, String> eventRecord(String topic, String eventType, String sourceService) throws Exception {
        EventEnvelope envelope = new EventEnvelope(
            UUID.randomUUID().toString(),
            eventType,
            "order",
            "ord-1",
            Instant.now(),
            "1",
            sourceService,
            UUID.randomUUID().toString(),
            objectMapper.valueToTree(Map.of("userId", UUID.randomUUID().toString()))
        );
        return new ConsumerRecord<>(topic, 0, 10L, "ord-1", objectMapper.writeValueAsString(envelope));
    }
}
