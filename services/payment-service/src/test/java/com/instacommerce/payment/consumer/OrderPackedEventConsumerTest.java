package com.instacommerce.payment.consumer;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.payment.domain.model.Payment;
import com.instacommerce.payment.domain.model.PaymentStatus;
import com.instacommerce.payment.repository.PaymentRepository;
import com.instacommerce.payment.service.PaymentService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderPackedEventConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentService paymentService;

    private OrderPackedEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new OrderPackedEventConsumer(objectMapper, paymentRepository, paymentService);
    }

    @Test
    @DisplayName("captures payment when receiving OrderPacked event for authorized payment")
    void capturesPaymentOnOrderPacked() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Payment payment = new Payment();
        payment.setId(paymentId);
        payment.setOrderId(orderId);
        payment.setStatus(PaymentStatus.AUTHORIZED);
        payment.setAmountCents(4500L);

        when(paymentRepository.findByOrderIdForUpdate(orderId)).thenReturn(Optional.of(payment));

        String json = objectMapper.writeValueAsString(Map.of(
            "eventType", "OrderPacked",
            "aggregateId", orderId.toString(),
            "payload", Map.of(
                "orderId", orderId.toString(),
                "fulfilledAmountCents", 4200L
            )
        ));

        ConsumerRecord<String, String> record = new ConsumerRecord<>("fulfillment.events", 0, 10L, "key", json);
        consumer.onFulfillmentEvent(record);

        verify(paymentRepository).findByOrderIdForUpdate(orderId);
        verify(paymentService).capture(eq(paymentId), eq(4200L), contains("packed-capture-"));
    }

    @Test
    @DisplayName("skips duplicate capture when payment is already CAPTURED")
    void skipsDuplicateWhenAlreadyCaptured() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Payment payment = new Payment();
        payment.setId(paymentId);
        payment.setOrderId(orderId);
        payment.setStatus(PaymentStatus.CAPTURED);

        when(paymentRepository.findByOrderIdForUpdate(orderId)).thenReturn(Optional.of(payment));

        String json = objectMapper.writeValueAsString(Map.of(
            "eventType", "OrderPacked",
            "payload", Map.of("orderId", orderId.toString())
        ));

        ConsumerRecord<String, String> record = new ConsumerRecord<>("fulfillment.events", 0, 11L, "key", json);
        consumer.onFulfillmentEvent(record);

        verify(paymentRepository).findByOrderIdForUpdate(orderId);
        verify(paymentService, never()).capture(any(), any(), any());
    }

    @Test
    @DisplayName("skips capture when payment is in non-capturable state like VOIDED")
    void skipsWhenVoided() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Payment payment = new Payment();
        payment.setId(paymentId);
        payment.setOrderId(orderId);
        payment.setStatus(PaymentStatus.VOIDED);

        when(paymentRepository.findByOrderIdForUpdate(orderId)).thenReturn(Optional.of(payment));

        String json = objectMapper.writeValueAsString(Map.of(
            "eventType", "OrderPacked",
            "payload", Map.of("orderId", orderId.toString())
        ));

        ConsumerRecord<String, String> record = new ConsumerRecord<>("fulfillment.events", 0, 12L, "key", json);
        consumer.onFulfillmentEvent(record);

        verify(paymentRepository).findByOrderIdForUpdate(orderId);
        verify(paymentService, never()).capture(any(), any(), any());
    }

    @Test
    @DisplayName("ignores non-OrderPacked events")
    void ignoresNonOrderPackedEvents() throws Exception {
        String json = objectMapper.writeValueAsString(Map.of(
            "eventType", "OrderDispatched",
            "payload", Map.of("orderId", UUID.randomUUID().toString())
        ));

        ConsumerRecord<String, String> record = new ConsumerRecord<>("fulfillment.events", 0, 13L, "key", json);
        consumer.onFulfillmentEvent(record);

        verifyNoInteractions(paymentRepository, paymentService);
    }
}
