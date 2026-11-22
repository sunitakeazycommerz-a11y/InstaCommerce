package com.instacommerce.routing.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.contracts.topics.TopicNames;
import com.instacommerce.routing.config.RoutingProperties;
import com.instacommerce.routing.domain.model.Delivery;
import com.instacommerce.routing.domain.model.DeliveryStatus;
import com.instacommerce.routing.dto.response.ETAEstimate;
import com.instacommerce.routing.repository.DeliveryRepository;
import com.instacommerce.routing.service.DeliveryService;
import com.instacommerce.routing.service.ETAService;
import com.instacommerce.routing.service.OutboxService;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.annotation.KafkaListener;

@ExtendWith(MockitoExtension.class)
class FulfillmentEventConsumerTest {

    @Mock
    private DeliveryRepository deliveryRepository;

    @Mock
    private ETAService etaService;

    @Mock
    private OutboxService outboxService;

    @Captor
    private ArgumentCaptor<Map<String, Object>> payloadCaptor;

    private DeliveryService deliveryService;
    private FulfillmentEventConsumer consumer;

    @BeforeEach
    void setUp() {
        deliveryService = new DeliveryService(
            deliveryRepository, etaService, outboxService, new RoutingProperties(), null);
        consumer = new FulfillmentEventConsumer(deliveryService, new ObjectMapper());
    }

    @Test
    @DisplayName("FulfillmentEventConsumer subscribes to both fulfillment.events and orders.events")
    void subscribesToFulfillmentAndOrdersTopics() throws NoSuchMethodException {
        Method listenerMethod = FulfillmentEventConsumer.class.getMethod("handleFulfillmentEvent", String.class);
        KafkaListener listener = listenerMethod.getAnnotation(KafkaListener.class);

        assertThat(listener).isNotNull();
        assertThat(listener.topics()).containsExactlyInAnyOrder(
            TopicNames.FULFILLMENT_EVENTS, TopicNames.ORDERS_EVENTS);
        assertThat(listener.groupId()).isEqualTo("routing-eta-service");
    }

    @Test
    @DisplayName("OrderCancelled from orders.events marks delivery FAILED with reason and stops ETA recalculations")
    void handlesOrderCancelledFromOrdersEventsAndRecordsReason() {
        UUID deliveryId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Delivery delivery = new Delivery();
        delivery.setId(deliveryId);
        delivery.setOrderId(orderId);
        delivery.setStatus(DeliveryStatus.EN_ROUTE);

        when(deliveryRepository.findByOrderId(orderId)).thenReturn(Optional.of(delivery));
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(deliveryRepository.save(any(Delivery.class))).thenAnswer(inv -> inv.getArgument(0));

        consumer.handleFulfillmentEvent("""
            {
              "id": "%s",
              "eventType": "OrderCancelled",
              "aggregateType": "Order",
              "aggregateId": "%s",
              "payload": {
                "orderId": "%s",
                "reason": "Customer requested cancellation",
                "reasonCode": "CUSTOMER_REQUEST"
              }
            }
            """.formatted(UUID.randomUUID(), orderId, orderId));

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        verify(outboxService).publish(
            eq("Delivery"), eq(deliveryId.toString()), eq("DeliveryStatusUpdated"), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
            .containsEntry("status", "FAILED")
            .containsEntry("orderId", orderId.toString())
            .containsEntry("reason", "Customer requested cancellation");

        deliveryService.updateETA(deliveryId,
            new ETAEstimate(10, 8, 12, new BigDecimal("2.0"), 0.1, Instant.now()));
        verify(outboxService, never()).publish(eq("Delivery"), eq(deliveryId.toString()), eq("EtaUpdated"), any());
    }

    @Test
    @DisplayName("Malformed payload orderId falls back to aggregateId and skips safely when both are invalid")
    void malformedUuidFallsBackToAggregateIdOrSkipsSafely() {
        UUID orderId = UUID.randomUUID();
        Delivery delivery = new Delivery();
        delivery.setId(UUID.randomUUID());
        delivery.setOrderId(orderId);
        delivery.setStatus(DeliveryStatus.RIDER_ASSIGNED);

        when(deliveryRepository.findByOrderId(orderId)).thenReturn(Optional.of(delivery));
        when(deliveryRepository.save(any(Delivery.class))).thenAnswer(inv -> inv.getArgument(0));

        consumer.handleFulfillmentEvent("""
            {
              "eventType": "OrderCancelled",
              "aggregateId": "%s",
              "payload": {
                "orderId": "not-a-uuid",
                "reasonCode": "PAYMENT_FAILED"
              }
            }
            """.formatted(orderId));

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        verify(outboxService).publish(
            eq("Delivery"), eq(delivery.getId().toString()), eq("DeliveryStatusUpdated"), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsEntry("reason", "PAYMENT_FAILED");

        consumer.handleFulfillmentEvent("""
            {
              "eventType": "OrderCancelled",
              "aggregateId": "still-not-a-uuid",
              "payload": {
                "orderId": "bad-uuid"
              }
            }
            """);

        verifyNoInteractions(etaService);
    }
}
