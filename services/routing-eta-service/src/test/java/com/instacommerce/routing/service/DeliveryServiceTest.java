package com.instacommerce.routing.service;

import com.instacommerce.routing.config.RoutingProperties;
import com.instacommerce.routing.domain.model.Delivery;
import com.instacommerce.routing.domain.model.DeliveryStatus;
import com.instacommerce.routing.dto.response.ETAEstimate;
import com.instacommerce.routing.exception.InvalidDeliveryStateException;
import com.instacommerce.routing.repository.DeliveryRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

        // Verify outbox event EtaUpdated
        ArgumentCaptor<Map<String, Object>> updateCaptor = ArgumentCaptor.captor();
        verify(outboxService).publish(
            eq("Delivery"), eq(deliveryId.toString()), eq("EtaUpdated"), updateCaptor.capture());
        assertEquals(12, updateCaptor.getValue().get("estimatedMinutes"));

        // Verify WebSocket broadcast
        verify(messagingTemplate).convertAndSend(eq("/topic/deliveries/" + deliveryId + "/eta"), any(Object.class));
    }

    @Test
    @DisplayName("SLA breach early warning issues configurable INR apology credit and ETABreachRisk outbox event")
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

        ETAEstimate breachEstimate = new ETAEstimate(
            28, 24, 32, new BigDecimal("5.500"), 0.95, Instant.now()
        );

        deliveryService.updateETA(deliveryId, breachEstimate);

        assertTrue(delivery.isBreachRisk());
        assertTrue(delivery.isCompensationTriggered());
        assertEquals(5000, delivery.getCompensationAmountCents());

        ArgumentCaptor<Map<String, Object>> breachCaptor = ArgumentCaptor.captor();
        verify(outboxService).publish(
            eq("Delivery"), eq(deliveryId.toString()), eq("ETABreachRisk"), breachCaptor.capture());

        Map<String, Object> payload = breachCaptor.getValue();
        assertEquals(5000, payload.get("autoCompensationCents"));
        assertEquals("INR", payload.get("currency"));
        assertTrue(String.valueOf(payload.get("customerMessage")).contains("₹50"));
        assertTrue((Boolean) payload.get("priorityEscalation"));
        assertNotNull(payload.get("customerMessage"));

        verify(messagingTemplate).convertAndSend(eq("/topic/deliveries/" + deliveryId + "/breach-risk"), any(Object.class));

        deliveryService.updateETA(deliveryId, breachEstimate);
        verify(outboxService).publish(
            eq("Delivery"), eq(deliveryId.toString()), eq("ETABreachRisk"), any());
    }

    @Test
    @DisplayName("createDelivery returns existing delivery idempotently even if coordinates are null")
    void createDeliveryReturnsExistingIdempotently() {
        UUID orderId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        Delivery existing = new Delivery();
        existing.setId(deliveryId);
        existing.setOrderId(orderId);
        existing.setRiderId(riderId);
        existing.setStatus(DeliveryStatus.RIDER_ASSIGNED);
        existing.setPickupLat(new BigDecimal("12.9716"));
        existing.setPickupLng(new BigDecimal("77.5946"));
        existing.setDropoffLat(new BigDecimal("12.9352"));
        existing.setDropoffLng(new BigDecimal("77.6245"));

        when(deliveryRepository.findByOrderId(orderId)).thenReturn(Optional.of(existing));

        var response = deliveryService.createDelivery(orderId, riderId, null, null, null, null, null);

        assertEquals(deliveryId, response.id());
        assertEquals(orderId, response.orderId());
        verifyNoInteractions(etaService);
        verify(deliveryRepository, never()).save(any());
    }

    @Test
    @DisplayName("createDelivery rejects null pickup or dropoff coordinates with IllegalArgumentException")
    void createDeliveryRejectsNullCoordinates() {
        UUID orderId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();

        when(deliveryRepository.findByOrderId(orderId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () ->
            deliveryService.createDelivery(
                orderId,
                riderId,
                storeId,
                new BigDecimal("12.9716"),
                new BigDecimal("77.5946"),
                null,
                new BigDecimal("77.6245")));

        verifyNoInteractions(etaService);
        verify(deliveryRepository, never()).save(any());
    }

    @Test
    @DisplayName("countPrecedingStops counts active deliveries for same rider that are closer to rider position")
    void countsPrecedingStopsForBatchDelivery() {
        UUID riderId = UUID.randomUUID();
        Delivery firstStop = new Delivery();
        firstStop.setId(UUID.randomUUID());
        firstStop.setRiderId(riderId);
        firstStop.setStatus(DeliveryStatus.EN_ROUTE);
        firstStop.setDropoffLat(new BigDecimal("12.9100"));
        firstStop.setDropoffLng(new BigDecimal("77.5100"));
        firstStop.setCreatedAt(Instant.parse("2026-11-19T10:00:00Z"));

        Delivery secondStop = new Delivery();
        secondStop.setId(UUID.randomUUID());
        secondStop.setRiderId(riderId);
        secondStop.setStatus(DeliveryStatus.EN_ROUTE);
        secondStop.setDropoffLat(new BigDecimal("12.9400"));
        secondStop.setDropoffLng(new BigDecimal("77.5400"));
        secondStop.setCreatedAt(Instant.parse("2026-11-19T10:01:00Z"));

        when(deliveryRepository.findByRiderIdAndStatusIn(eq(riderId), any()))
            .thenReturn(List.of(firstStop, secondStop));

        assertEquals(0, deliveryService.countPrecedingStops(firstStop, 12.9000, 77.5000));
        assertEquals(1, deliveryService.countPrecedingStops(secondStop, 12.9000, 77.5000));
    }

    @Test
    @DisplayName("advanceOrderDelivered routes PENDING delivery through validated transitions and emits status events")
    void advanceOrderDeliveredEmitsStatusUpdatesAndValidatesTransitions() {
        UUID deliveryId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Delivery delivery = new Delivery();
        delivery.setId(deliveryId);
        delivery.setOrderId(orderId);
        delivery.setStatus(DeliveryStatus.PENDING);

        when(deliveryRepository.findByOrderId(orderId)).thenReturn(Optional.of(delivery));
        when(deliveryRepository.save(any(Delivery.class))).thenAnswer(i -> i.getArgument(0));

        deliveryService.advanceOrderDelivered(orderId);

        assertEquals(DeliveryStatus.DELIVERED, delivery.getStatus());
        verify(outboxService, times(3)).publish(
            eq("Delivery"), eq(deliveryId.toString()), eq("DeliveryStatusUpdated"), any());
        verify(outboxService).publish(
            eq("Delivery"), eq(deliveryId.toString()), eq("DeliveryCompleted"), any());

        Delivery failed = new Delivery();
        failed.setId(UUID.randomUUID());
        failed.setOrderId(UUID.randomUUID());
        failed.setStatus(DeliveryStatus.FAILED);
        when(deliveryRepository.findByOrderId(failed.getOrderId())).thenReturn(Optional.of(failed));

        assertThrows(InvalidDeliveryStateException.class,
            () -> deliveryService.advanceOrderDelivered(failed.getOrderId()));
    }
}
