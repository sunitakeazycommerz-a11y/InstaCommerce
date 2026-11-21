package com.instacommerce.fraud.consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.fraud.service.VelocityService;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;

class FraudKafkaConsumerPropagationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void orderEventConsumerPropagatesTransientFailureAndMalformedJson() {
        VelocityService velocityService = mock(VelocityService.class);
        OrderEventConsumer consumer = new OrderEventConsumer(objectMapper, velocityService);
        UUID userId = UUID.randomUUID();
        doThrow(new TransientDataAccessResourceException("db busy"))
                .when(velocityService)
                .incrementCounter("USER", userId.toString(), "ORDERS_5M");

        String payload = """
                {"id":"evt-1","eventType":"OrderPlaced","payload":{"userId":"%s","orderId":"ord-1","totalCents":5000}}
                """.formatted(userId);
        ConsumerRecord<String, String> validRecord = new ConsumerRecord<>("orders.events", 0, 10L, "k1", payload);
        assertThatThrownBy(() -> consumer.onOrderEvent(validRecord))
                .isInstanceOf(TransientDataAccessResourceException.class);

        ConsumerRecord<String, String> badJson = new ConsumerRecord<>("orders.events", 0, 11L, "k2", "{not-json");
        assertThatThrownBy(() -> consumer.onOrderEvent(badJson))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void paymentEventConsumerPropagatesTransientFailureAndMalformedJson() {
        VelocityService velocityService = mock(VelocityService.class);
        PaymentEventConsumer consumer = new PaymentEventConsumer(objectMapper, velocityService);
        UUID userId = UUID.randomUUID();
        doThrow(new TransientDataAccessResourceException("db busy"))
                .when(velocityService)
                .incrementCounter("USER", userId.toString(), "FAILED_PAYMENTS_5M");

        String payload = """
                {"id":"evt-2","eventType":"PaymentFailed","payload":{"userId":"%s"}}
                """.formatted(userId);
        ConsumerRecord<String, String> validRecord = new ConsumerRecord<>("payments.events", 0, 10L, "k1", payload);
        assertThatThrownBy(() -> consumer.onPaymentEvent(validRecord))
                .isInstanceOf(TransientDataAccessResourceException.class);

        ConsumerRecord<String, String> badJson = new ConsumerRecord<>("payments.events", 0, 11L, "k2", "{not-json");
        assertThatThrownBy(() -> consumer.onPaymentEvent(badJson))
                .isInstanceOf(IllegalStateException.class);
    }
}
