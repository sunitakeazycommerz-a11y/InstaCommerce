package com.instacommerce.routing.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.routing.config.RoutingProperties;
import com.instacommerce.routing.domain.model.Delivery;
import com.instacommerce.routing.domain.model.DeliveryStatus;
import com.instacommerce.routing.domain.model.DeliveryTracking;
import com.instacommerce.routing.dto.response.ETAEstimate;
import com.instacommerce.routing.repository.DeliveryRepository;
import com.instacommerce.routing.repository.DeliveryTrackingRepository;
import com.instacommerce.routing.service.DeliveryService;
import com.instacommerce.routing.service.ETAService;
import com.instacommerce.routing.service.OutboxService;
import com.instacommerce.routing.service.TrackingService;
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
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GeofenceEventConsumerTest {

    @Mock
    private DeliveryRepository deliveryRepository;

    @Mock
    private DeliveryTrackingRepository trackingRepository;

    @Mock
    private ETAService etaService;

    @Mock
    private OutboxService outboxService;

    @Captor
    private ArgumentCaptor<Map<String, Object>> payloadCaptor;

    private DeliveryService deliveryService;
    private TrackingService trackingService;
    private GeofenceEventConsumer geofenceConsumer;
    private FulfillmentEventConsumer fulfillmentConsumer;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        deliveryService = new DeliveryService(
            deliveryRepository, etaService, outboxService, new RoutingProperties(), null);
        trackingService = new TrackingService(
            trackingRepository, deliveryRepository, deliveryService, null);
        geofenceConsumer = new GeofenceEventConsumer(deliveryService, trackingService, objectMapper);
        fulfillmentConsumer = new FulfillmentEventConsumer(deliveryService, objectMapper);
    }

    @Test
    @DisplayName("ENTERED_STORE and NEAR_DELIVERY transitions update tracking, emit DeliveryStatusUpdated, and are idempotent")
    void handlesEnteredStoreAndNearDeliveryTransitionsIdempotently() {
        UUID deliveryId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();

        Delivery delivery = new Delivery();
        delivery.setId(deliveryId);
        delivery.setOrderId(orderId);
        delivery.setRiderId(riderId);
        delivery.setStoreId(storeId);
        delivery.setStatus(DeliveryStatus.RIDER_ASSIGNED);
        delivery.setDropoffLat(new BigDecimal("12.9352"));
        delivery.setDropoffLng(new BigDecimal("77.6245"));

        when(deliveryRepository.findByRiderIdAndStatusIn(eq(riderId), any())).thenReturn(List.of(delivery));
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(deliveryRepository.save(any(Delivery.class))).thenAnswer(inv -> inv.getArgument(0));
        when(trackingRepository.save(any(DeliveryTracking.class))).thenAnswer(inv -> inv.getArgument(0));

        String enteredStoreEnvelope = """
            {
              "id": "geo_1",
              "eventType": "RiderGeofenceTransition",
              "aggregateType": "RiderGeofence",
              "aggregateId": "%s",
              "payload": {
                "rider_id": "%s",
                "zone_id": "%s",
                "event_type": "ENTERED_STORE",
                "h3_index": "8928308280fffff",
                "timestamp": "2026-11-21T16:35:00Z",
                "lat": 12.9716,
                "lng": 77.5946
              }
            }
            """.formatted(riderId, riderId, storeId);

        geofenceConsumer.handleGeofenceEvent(enteredStoreEnvelope);
        geofenceConsumer.handleGeofenceEvent(enteredStoreEnvelope);

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.PICKED_UP);
        verify(trackingRepository, times(1)).save(any(DeliveryTracking.class));
        verify(outboxService, times(1)).publish(
            eq("Delivery"), eq(deliveryId.toString()), eq("DeliveryStatusUpdated"), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsEntry("status", "PICKED_UP");

        delivery.setStatus(DeliveryStatus.EN_ROUTE);

        String nearDeliveryEnvelope = """
            {
              "id": "geo_2",
              "eventType": "RiderGeofenceTransition",
              "aggregateType": "RiderGeofence",
              "aggregateId": "%s",
              "payload": {
                "rider_id": "%s",
                "zone_id": "zone-cust-1",
                "event_type": "NEAR_DELIVERY",
                "h3_index": "8928308280fffff",
                "timestamp": "2026-11-21T16:45:00Z",
                "lat": 12.9351,
                "lng": 77.6244
              }
            }
            """.formatted(riderId, riderId);

        geofenceConsumer.handleGeofenceEvent(nearDeliveryEnvelope);
        geofenceConsumer.handleGeofenceEvent(nearDeliveryEnvelope);

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.NEAR_DESTINATION);
        verify(trackingRepository, times(2)).save(any(DeliveryTracking.class));
        verify(outboxService, times(2)).publish(
            eq("Delivery"), eq(deliveryId.toString()), eq("DeliveryStatusUpdated"), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsEntry("status", "NEAR_DESTINATION");
    }

    @Test
    @DisplayName("FulfillmentEventConsumer marks delivery FAILED on DeliveryFailed and PickTaskCancelled so ETA updates stop")
    void fulfillmentConsumerMarksDeliveryFailedAndStopsEtaRecalculation() {
        UUID deliveryId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Delivery delivery = new Delivery();
        delivery.setId(deliveryId);
        delivery.setOrderId(orderId);
        delivery.setStatus(DeliveryStatus.EN_ROUTE);

        when(deliveryRepository.findByOrderId(orderId)).thenReturn(Optional.of(delivery));
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(deliveryRepository.save(any(Delivery.class))).thenAnswer(inv -> inv.getArgument(0));

        fulfillmentConsumer.handleFulfillmentEvent("""
            {"eventType":"DeliveryFailed","payload":{"orderId":"%s","reason":"Customer unreachable"}}
            """.formatted(orderId));

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        verify(outboxService, times(1)).publish(
            eq("Delivery"), eq(deliveryId.toString()), eq("DeliveryStatusUpdated"), any());

        fulfillmentConsumer.handleFulfillmentEvent("""
            {"eventType":"PickTaskCancelled","payload":{"orderId":"%s","reason":"Cancelled"}}
            """.formatted(orderId));

        verify(outboxService, times(1)).publish(
            eq("Delivery"), eq(deliveryId.toString()), eq("DeliveryStatusUpdated"), any());

        ETAEstimate estimate = new ETAEstimate(10, 8, 12, new BigDecimal("2.0"), 0.1, Instant.now());
        deliveryService.updateETA(deliveryId, estimate);
        verify(outboxService, never()).publish(eq("Delivery"), eq(deliveryId.toString()), eq("EtaUpdated"), any());
    }

    @Test
    @DisplayName("ENTERED_STORE matches storeId only and leaves other-store, ambiguous, and unowned deliveries untouched")
    void enteredStoreLeavesUnrelatedBatchedStopsUntouched() {
        UUID riderId = UUID.randomUUID();
        UUID storeA = UUID.randomUUID();
        UUID storeB = UUID.randomUUID();

        Delivery stopStoreA = new Delivery();
        stopStoreA.setId(UUID.randomUUID());
        stopStoreA.setOrderId(UUID.randomUUID());
        stopStoreA.setRiderId(riderId);
        stopStoreA.setStoreId(storeA);
        stopStoreA.setStatus(DeliveryStatus.RIDER_ASSIGNED);

        Delivery stopStoreB = new Delivery();
        stopStoreB.setId(UUID.randomUUID());
        stopStoreB.setOrderId(UUID.randomUUID());
        stopStoreB.setRiderId(riderId);
        stopStoreB.setStoreId(storeB);
        stopStoreB.setStatus(DeliveryStatus.RIDER_ASSIGNED);

        when(deliveryRepository.findByRiderIdAndStatusIn(eq(riderId), any()))
            .thenReturn(List.of(stopStoreA, stopStoreB));
        when(deliveryRepository.findById(stopStoreA.getId())).thenReturn(Optional.of(stopStoreA));
        when(deliveryRepository.save(any(Delivery.class))).thenAnswer(inv -> inv.getArgument(0));
        when(trackingRepository.save(any(DeliveryTracking.class))).thenAnswer(inv -> inv.getArgument(0));

        geofenceConsumer.handleGeofenceEvent("""
            {
              "eventType": "RiderGeofenceTransition",
              "payload": {
                "rider_id": "%s",
                "zone_id": "%s",
                "event_type": "ENTERED_STORE",
                "lat": 12.9716,
                "lng": 77.5946
              }
            }
            """.formatted(riderId, storeA));

        assertThat(stopStoreA.getStatus()).isEqualTo(DeliveryStatus.PICKED_UP);
        assertThat(stopStoreB.getStatus()).isEqualTo(DeliveryStatus.RIDER_ASSIGNED);
        verify(outboxService, times(1)).publish(
            eq("Delivery"), eq(stopStoreA.getId().toString()), eq("DeliveryStatusUpdated"), any());
        verify(outboxService, never()).publish(
            eq("Delivery"), eq(stopStoreB.getId().toString()), any(), any());

        geofenceConsumer.handleGeofenceEvent("""
            {
              "eventType": "RiderGeofenceTransition",
              "payload": {
                "rider_id": "%s",
                "zone_id": "non-uuid-zone",
                "event_type": "ENTERED_STORE",
                "lat": 12.9716,
                "lng": 77.5946
              }
            }
            """.formatted(riderId));

        assertThat(stopStoreB.getStatus()).isEqualTo(DeliveryStatus.RIDER_ASSIGNED);

        UUID unmatchedZone = UUID.randomUUID();
        geofenceConsumer.handleGeofenceEvent("""
            {
              "eventType": "RiderGeofenceTransition",
              "payload": {
                "zone_id": "%s",
                "event_type": "ENTERED_STORE",
                "lat": 12.9716,
                "lng": 77.5946
              }
            }
            """.formatted(unmatchedZone));

        verify(deliveryRepository, never()).findById(unmatchedZone);
        verify(deliveryRepository, never()).findByOrderId(unmatchedZone);
    }

    @Test
    @DisplayName("NEAR_DELIVERY uses (lat, lng) proximity to disambiguate batched stops and never force-steps PENDING/RIDER_ASSIGNED")
    void nearDeliveryDisambiguatesBatchedStopsByProximityWithoutForceStepping() {
        UUID riderId = UUID.randomUUID();

        Delivery stop1EnRouteNear = new Delivery();
        stop1EnRouteNear.setId(UUID.randomUUID());
        stop1EnRouteNear.setOrderId(UUID.randomUUID());
        stop1EnRouteNear.setRiderId(riderId);
        stop1EnRouteNear.setStatus(DeliveryStatus.EN_ROUTE);
        stop1EnRouteNear.setDropoffLat(new BigDecimal("12.9352"));
        stop1EnRouteNear.setDropoffLng(new BigDecimal("77.6245"));

        Delivery stop2EnRouteFar = new Delivery();
        stop2EnRouteFar.setId(UUID.randomUUID());
        stop2EnRouteFar.setOrderId(UUID.randomUUID());
        stop2EnRouteFar.setRiderId(riderId);
        stop2EnRouteFar.setStatus(DeliveryStatus.EN_ROUTE);
        stop2EnRouteFar.setDropoffLat(new BigDecimal("12.9800"));
        stop2EnRouteFar.setDropoffLng(new BigDecimal("77.6800"));

        Delivery stop3PendingSameDropoff = new Delivery();
        stop3PendingSameDropoff.setId(UUID.randomUUID());
        stop3PendingSameDropoff.setOrderId(UUID.randomUUID());
        stop3PendingSameDropoff.setRiderId(riderId);
        stop3PendingSameDropoff.setStatus(DeliveryStatus.PENDING);
        stop3PendingSameDropoff.setDropoffLat(new BigDecimal("12.9352"));
        stop3PendingSameDropoff.setDropoffLng(new BigDecimal("77.6245"));

        Delivery stop4AssignedSameDropoff = new Delivery();
        stop4AssignedSameDropoff.setId(UUID.randomUUID());
        stop4AssignedSameDropoff.setOrderId(UUID.randomUUID());
        stop4AssignedSameDropoff.setRiderId(riderId);
        stop4AssignedSameDropoff.setStatus(DeliveryStatus.RIDER_ASSIGNED);
        stop4AssignedSameDropoff.setDropoffLat(new BigDecimal("12.9352"));
        stop4AssignedSameDropoff.setDropoffLng(new BigDecimal("77.6245"));

        when(deliveryRepository.findByRiderIdAndStatusIn(eq(riderId), any()))
            .thenReturn(List.of(stop1EnRouteNear, stop2EnRouteFar, stop3PendingSameDropoff, stop4AssignedSameDropoff));
        when(deliveryRepository.findById(stop1EnRouteNear.getId())).thenReturn(Optional.of(stop1EnRouteNear));
        when(deliveryRepository.save(any(Delivery.class))).thenAnswer(inv -> inv.getArgument(0));
        when(trackingRepository.save(any(DeliveryTracking.class))).thenAnswer(inv -> inv.getArgument(0));

        geofenceConsumer.handleGeofenceEvent("""
            {
              "eventType": "RiderGeofenceTransition",
              "payload": {
                "rider_id": "%s",
                "zone_id": "%s",
                "event_type": "NEAR_DELIVERY",
                "lat": 12.9351,
                "lng": 77.6244
              }
            }
            """.formatted(riderId, UUID.randomUUID()));

        assertThat(stop1EnRouteNear.getStatus()).isEqualTo(DeliveryStatus.NEAR_DESTINATION);
        assertThat(stop2EnRouteFar.getStatus()).isEqualTo(DeliveryStatus.EN_ROUTE);
        assertThat(stop3PendingSameDropoff.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(stop4AssignedSameDropoff.getStatus()).isEqualTo(DeliveryStatus.RIDER_ASSIGNED);

        verify(outboxService, times(1)).publish(
            eq("Delivery"), eq(stop1EnRouteNear.getId().toString()), eq("DeliveryStatusUpdated"), any());
        verify(outboxService, never()).publish(
            eq("Delivery"), eq(stop2EnRouteFar.getId().toString()), any(), any());
        verify(outboxService, never()).publish(
            eq("Delivery"), eq(stop3PendingSameDropoff.getId().toString()), any(), any());
        verify(outboxService, never()).publish(
            eq("Delivery"), eq(stop4AssignedSameDropoff.getId().toString()), any(), any());
        verify(trackingRepository, times(1)).save(any(DeliveryTracking.class));
    }

    @Test
    @DisplayName("NEAR_DELIVERY with exact orderId transitions EN_ROUTE delivery but does not force-step RIDER_ASSIGNED")
    void nearDeliveryExactOrderIdNeverForceStepsPreTransitDelivery() {
        UUID riderId = UUID.randomUUID();
        UUID assignedOrderId = UUID.randomUUID();
        Delivery assignedDelivery = new Delivery();
        assignedDelivery.setId(UUID.randomUUID());
        assignedDelivery.setOrderId(assignedOrderId);
        assignedDelivery.setRiderId(riderId);
        assignedDelivery.setStatus(DeliveryStatus.RIDER_ASSIGNED);

        when(deliveryRepository.findByOrderId(assignedOrderId)).thenReturn(Optional.of(assignedDelivery));

        geofenceConsumer.handleGeofenceEvent("""
            {
              "eventType": "RiderGeofenceTransition",
              "payload": {
                "rider_id": "%s",
                "orderId": "%s",
                "event_type": "NEAR_DELIVERY",
                "lat": 12.9351,
                "lng": 77.6244
              }
            }
            """.formatted(riderId, assignedOrderId));

        assertThat(assignedDelivery.getStatus()).isEqualTo(DeliveryStatus.RIDER_ASSIGNED);
        verifyNoInteractions(outboxService);
        verifyNoInteractions(trackingRepository);

        UUID enRouteOrderId = UUID.randomUUID();
        Delivery enRouteDelivery = new Delivery();
        enRouteDelivery.setId(UUID.randomUUID());
        enRouteDelivery.setOrderId(enRouteOrderId);
        enRouteDelivery.setRiderId(riderId);
        enRouteDelivery.setStatus(DeliveryStatus.EN_ROUTE);

        when(deliveryRepository.findByOrderId(enRouteOrderId)).thenReturn(Optional.of(enRouteDelivery));
        when(deliveryRepository.findById(enRouteDelivery.getId())).thenReturn(Optional.of(enRouteDelivery));
        when(deliveryRepository.save(any(Delivery.class))).thenAnswer(inv -> inv.getArgument(0));
        when(trackingRepository.save(any(DeliveryTracking.class))).thenAnswer(inv -> inv.getArgument(0));

        geofenceConsumer.handleGeofenceEvent("""
            {
              "eventType": "RiderGeofenceTransition",
              "payload": {
                "rider_id": "%s",
                "orderId": "%s",
                "event_type": "NEAR_DELIVERY",
                "lat": 12.9351,
                "lng": 77.6244
              }
            }
            """.formatted(riderId, enRouteOrderId));

        assertThat(enRouteDelivery.getStatus()).isEqualTo(DeliveryStatus.NEAR_DESTINATION);
        verify(outboxService, times(1)).publish(
            eq("Delivery"), eq(enRouteDelivery.getId().toString()), eq("DeliveryStatusUpdated"), any());
        verify(trackingRepository, times(1)).save(any(DeliveryTracking.class));
    }
}
