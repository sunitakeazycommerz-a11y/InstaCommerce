package com.instacommerce.routing.service;

import com.instacommerce.routing.config.RoutingProperties;
import com.instacommerce.routing.domain.model.Delivery;
import com.instacommerce.routing.domain.model.DeliveryStatus;
import com.instacommerce.routing.dto.response.ETAEstimate;
import com.instacommerce.routing.repository.DeliveryRepository;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryServiceTest {

    @Mock
    private DeliveryRepository deliveryRepository;

    @Mock
    private ETAService etaService;

    @Mock
    private OutboxService outboxService;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private RoutingProperties routingProperties;
    private DeliveryService deliveryService;

    @BeforeEach
    void setUp() {
        routingProperties = new RoutingProperties();
        routingProperties.getBreach().setSlaThresholdMinutes(20);
        routingProperties.getBreach().setBreachAlertThreshold(0.7);

        deliveryService = new DeliveryService(
            deliveryRepository,
            etaService,
            outboxService,
            routingProperties,
            messagingTemplate
        );
    }

    @Test
    @DisplayName("Continuous ETA update emits ETA_UPDATED outbox event and WebSocket message (Issue #595)")
    void shouldEmitContinuousEtaUpdates() {
        UUID deliveryId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();

        Delivery delivery = new Delivery();
        delivery.setId(deliveryId);
        delivery.setOrderId(orderId);
        delivery.setRiderId(riderId);
        delivery.setStatus(DeliveryStatus.EN_ROUTE);
        delivery.setEstimatedMinutes(15);

        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(deliveryRepository.save(any(Delivery.class))).thenAnswer(i -> i.getArgument(0));

        ETAEstimate estimate = new ETAEstimate(
            12, 10, 15, new BigDecimal("3.200"), 0.15, Instant.now()
        );

        deliveryService.updateETA(deliveryId, estimate);

        // Verify outbox event ETA_UPDATED
        ArgumentCaptor<Map<String, Object>> updateCaptor = ArgumentCaptor.forClass(Map.class);
        verify(outboxService).publish(
            eq("Delivery"), eq(deliveryId.toString()), eq("ETA_UPDATED"), updateCaptor.capture());
        assertEquals(12, updateCaptor.getValue().get("estimatedMinutes"));

        // Verify WebSocket broadcast
        verify(messagingTemplate).convertAndSend(eq("/topic/deliveries/" + deliveryId + "/eta"), any(Object.class));
    }

    @Test
    @DisplayName("SLA breach early warning issues automated $2 apology credit and ETA_BREACH_RISK outbox event (Issue #596)")
    void shouldTriggerSlaBreachEarlyWarningAndApologyCredit() {
        UUID deliveryId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();

        Delivery delivery = new Delivery();
        delivery.setId(deliveryId);
        delivery.setOrderId(orderId);
        delivery.setRiderId(riderId);
        delivery.setStatus(DeliveryStatus.EN_ROUTE);
        delivery.setEstimatedMinutes(18);
        delivery.setCompensationTriggered(false);

        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(deliveryRepository.save(any(Delivery.class))).thenAnswer(i -> i.getArgument(0));

        // High breach probability (0.95) with ETA exceeding SLA
        ETAEstimate breachEstimate = new ETAEstimate(
            28, 24, 32, new BigDecimal("5.500"), 0.95, Instant.now()
        );

        deliveryService.updateETA(deliveryId, breachEstimate);

        // Verify delivery marked as breach risk with $2 credit
        assertTrue(delivery.isBreachRisk());
        assertTrue(delivery.isCompensationTriggered());
        assertEquals(200, delivery.getCompensationAmountCents());

        // Verify ETA_BREACH_RISK event published
        ArgumentCaptor<Map<String, Object>> breachCaptor = ArgumentCaptor.forClass(Map.class);
        verify(outboxService).publish(
            eq("Delivery"), eq(deliveryId.toString()), eq("ETA_BREACH_RISK"), breachCaptor.capture());

        Map<String, Object> payload = breachCaptor.getValue();
        assertEquals(200, payload.get("autoCompensationCents"));
        assertTrue((Boolean) payload.get("priorityEscalation"));
        assertNotNull(payload.get("customerMessage"));

        // Verify breach risk WebSocket broadcast
        verify(messagingTemplate).convertAndSend(eq("/topic/deliveries/" + deliveryId + "/breach-risk"), any(Object.class));
    }
}
