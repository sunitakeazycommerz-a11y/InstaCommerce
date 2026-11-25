package com.instacommerce.riderfleet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.instacommerce.riderfleet.client.DispatchOptimizerClient;
import com.instacommerce.riderfleet.config.RiderFleetProperties;
import com.instacommerce.riderfleet.domain.model.Rider;
import com.instacommerce.riderfleet.domain.model.RiderAssignment;
import com.instacommerce.riderfleet.domain.model.RiderAvailability;
import com.instacommerce.riderfleet.domain.model.RiderStatus;
import com.instacommerce.riderfleet.domain.model.VehicleType;
import com.instacommerce.riderfleet.repository.RiderAssignmentRepository;
import com.instacommerce.riderfleet.repository.RiderAvailabilityRepository;
import com.instacommerce.riderfleet.repository.RiderRepository;
import java.math.BigDecimal;
import java.time.Clock;
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
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class RiderAssignmentServiceTest {

    @Mock private RiderRepository riderRepository;
    @Mock private RiderAvailabilityRepository availabilityRepository;
    @Mock private RiderAssignmentRepository assignmentRepository;
    @Mock private OutboxService outboxService;
    @Mock private DispatchOptimizerClient dispatchOptimizerClient;

    @Captor private ArgumentCaptor<Map<String, Object>> payloadCaptor;

    private RiderFleetProperties properties;
    private RiderAssignmentService service;

    @BeforeEach
    void setUp() {
        properties = new RiderFleetProperties();
        service = new RiderAssignmentService(
            riderRepository,
            availabilityRepository,
            assignmentRepository,
            outboxService,
            properties,
            dispatchOptimizerClient,
            null,
            null,
            null,
            Clock.systemUTC()
        );
    }

    @Test
    @DisplayName("completeDelivery restores rider to ACTIVE and available true")
    void completeDeliveryRestoresRiderToActive() {
        UUID orderId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();

        RiderAssignment assignment = new RiderAssignment();
        assignment.setOrderId(orderId);
        assignment.setRiderId(riderId);
        assignment.setStoreId(storeId);

        Rider rider = new Rider();
        rider.setId(riderId);
        rider.setStatus(RiderStatus.ON_DELIVERY);

        RiderAvailability availability = new RiderAvailability();
        availability.setRiderId(riderId);
        availability.setAvailable(false);

        when(assignmentRepository.findByOrderId(orderId)).thenReturn(Optional.of(assignment));
        when(assignmentRepository.markCompletedIfPending(eq(orderId), any())).thenReturn(1);
        when(riderRepository.findById(riderId)).thenReturn(Optional.of(rider));
        when(availabilityRepository.findByRiderId(riderId)).thenReturn(Optional.of(availability));

        service.completeDelivery(orderId);

        assertThat(rider.getStatus()).isEqualTo(RiderStatus.ACTIVE);
        assertThat(availability.isAvailable()).isTrue();
        verify(riderRepository).save(rider);
        verify(availabilityRepository).save(availability);
        verify(outboxService).publish(eq("Rider"), eq(riderId.toString()), eq("DeliveryCompleted"), any());
    }

    @Test
    @DisplayName("completeDelivery skips duplicate redelivery atomically when already completed")
    void completeDeliveryDuplicateDeliveryIsIdempotent() {
        UUID orderId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        RiderAssignment assignment = new RiderAssignment();
        assignment.setOrderId(orderId);
        assignment.setRiderId(riderId);
        assignment.setStoreId(UUID.randomUUID());

        when(assignmentRepository.findByOrderId(orderId)).thenReturn(Optional.of(assignment));
        when(assignmentRepository.markCompletedIfPending(eq(orderId), any())).thenReturn(0);

        service.completeDelivery(orderId);

        verify(riderRepository, never()).findById(any());
        verify(riderRepository, never()).save(any());
        verify(availabilityRepository, never()).save(any());
        verify(outboxService, never()).publish(any(), any(), any(), any());
    }

    @Test
    @DisplayName("cancelAssignment restores rider to ACTIVE and available true")
    void cancelAssignmentRestoresRiderToActive() {
        UUID orderId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();

        RiderAssignment assignment = new RiderAssignment();
        assignment.setOrderId(orderId);
        assignment.setRiderId(riderId);
        assignment.setStoreId(storeId);

        Rider rider = new Rider();
        rider.setId(riderId);
        rider.setStatus(RiderStatus.ON_DELIVERY);

        RiderAvailability availability = new RiderAvailability();
        availability.setRiderId(riderId);
        availability.setAvailable(false);

        when(assignmentRepository.findByOrderId(orderId)).thenReturn(Optional.of(assignment));
        when(riderRepository.findById(riderId)).thenReturn(Optional.of(rider));
        when(availabilityRepository.findByRiderId(riderId)).thenReturn(Optional.of(availability));

        service.cancelAssignment(orderId, "Customer cancelled order");

        assertThat(rider.getStatus()).isEqualTo(RiderStatus.ACTIVE);
        assertThat(availability.isAvailable()).isTrue();
        verify(riderRepository).save(rider);
        verify(availabilityRepository).save(availability);
        verify(outboxService).publish(eq("Rider"), eq(riderId.toString()), eq("RiderAssignmentCancelled"), any());
    }

    @Test
    @DisplayName("completeDelivery with unknown order does nothing idempotently")
    void completeDeliveryUnknownOrder() {
        UUID orderId = UUID.randomUUID();
        when(assignmentRepository.findByOrderId(orderId)).thenReturn(Optional.empty());

        service.completeDelivery(orderId);

        verify(riderRepository, never()).save(any());
        verify(availabilityRepository, never()).save(any());
        verify(outboxService, never()).publish(any(), any(), any(), any());
    }

    @Test
    @DisplayName("assignRider emits pickup coordinates in RiderAssigned outbox payload")
    void assignRiderEmitsPickupCoordinatesInOutboxPayload() {
        UUID orderId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        BigDecimal pickupLat = new BigDecimal("12.9716");
        BigDecimal pickupLng = new BigDecimal("77.5946");

        RiderAvailability availability = new RiderAvailability();
        availability.setRiderId(riderId);
        availability.setStoreId(storeId);
        availability.setAvailable(true);

        Rider rider = new Rider();
        rider.setId(riderId);
        rider.setName("Asha Rider");
        rider.setStatus(RiderStatus.ACTIVE);

        when(assignmentRepository.existsByOrderId(orderId)).thenReturn(false);
        when(availabilityRepository.findEligibleNearestCandidates(
            eq(pickupLat), eq(pickupLng), eq(5.0), eq(storeId), eq(0),
            anyInt(), anyInt(), any(), eq(false), any(), anyInt()))
            .thenReturn(List.of(availability));
        when(riderRepository.findAllById(List.of(riderId))).thenReturn(List.of(rider));
        when(availabilityRepository.findAvailableByRiderIdForUpdateSkipLocked(riderId))
            .thenReturn(Optional.of(availability));

        UUID assignedRiderId = service.assignRider(orderId, storeId, pickupLat, pickupLng);

        assertThat(assignedRiderId).isEqualTo(riderId);
        verify(outboxService).publish(
            eq("Rider"), eq(riderId.toString()), eq("RiderAssigned"), payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload)
            .containsEntry("riderId", riderId)
            .containsEntry("orderId", orderId)
            .containsEntry("storeId", storeId)
            .containsEntry("pickupLat", pickupLat)
            .containsEntry("pickupLng", pickupLng)
            .doesNotContainKey("dropoffLat")
            .doesNotContainKey("dropoffLng");
    }

    @Test
    @DisplayName("assignRider emits dropoff coordinates in RiderAssigned outbox payload when provided")
    void assignRiderEmitsDropoffCoordinatesWhenPresent() {
        UUID orderId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        BigDecimal pickupLat = new BigDecimal("12.9716");
        BigDecimal pickupLng = new BigDecimal("77.5946");
        BigDecimal dropoffLat = new BigDecimal("12.9352");
        BigDecimal dropoffLng = new BigDecimal("77.6245");

        RiderAvailability availability = new RiderAvailability();
        availability.setRiderId(riderId);
        availability.setStoreId(storeId);
        availability.setAvailable(true);

        Rider rider = new Rider();
        rider.setId(riderId);
        rider.setName("Vikram Rider");
        rider.setStatus(RiderStatus.ACTIVE);
        rider.setVehicleType(VehicleType.BICYCLE);

        when(assignmentRepository.existsByOrderId(orderId)).thenReturn(false);
        when(availabilityRepository.findEligibleNearestCandidates(
            eq(pickupLat), eq(pickupLng), eq(5.0), eq(storeId), eq(0),
            anyInt(), anyInt(), any(), eq(false), any(), anyInt()))
            .thenReturn(List.of(availability));
        when(riderRepository.findAllById(List.of(riderId))).thenReturn(List.of(rider));
        when(availabilityRepository.findAvailableByRiderIdForUpdateSkipLocked(riderId))
            .thenReturn(Optional.of(availability));

        UUID assignedRiderId = service.assignRider(
            orderId, storeId, pickupLat, pickupLng, dropoffLat, dropoffLng);

        assertThat(assignedRiderId).isEqualTo(riderId);
        verify(outboxService).publish(
            eq("Rider"), eq(riderId.toString()), eq("RiderAssigned"), payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload)
            .containsEntry("riderId", riderId)
            .containsEntry("orderId", orderId)
            .containsEntry("storeId", storeId)
            .containsEntry("vehicleType", "BICYCLE")
            .containsEntry("pickupLat", pickupLat)
            .containsEntry("pickupLng", pickupLng)
            .containsEntry("dropoffLat", dropoffLat)
            .containsEntry("dropoffLng", dropoffLng);
    }

    @Test
    @DisplayName("assignRider queries Redis GEORADIUS riders:active before DB fallback")
    @SuppressWarnings("unchecked")
    void assignRiderUsesRedisGeoRadiusBeforeDbFallback() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        GeoOperations<String, String> geoOps = mock(GeoOperations.class);
        when(redisTemplate.opsForGeo()).thenReturn(geoOps);

        RiderAssignmentService redisBackedService = new RiderAssignmentService(
            riderRepository, availabilityRepository, assignmentRepository,
            outboxService, properties, dispatchOptimizerClient,
            null, redisTemplate, null, Clock.systemUTC());

        UUID orderId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        BigDecimal pickupLat = new BigDecimal("12.9716");
        BigDecimal pickupLng = new BigDecimal("77.5946");

        RiderAvailability availability = new RiderAvailability();
        availability.setRiderId(riderId);
        availability.setStoreId(storeId);
        availability.setAvailable(true);

        Rider rider = new Rider();
        rider.setId(riderId);
        rider.setName("Geo Rider");
        rider.setStatus(RiderStatus.ACTIVE);
        rider.setVehicleType(VehicleType.CAR);

        GeoResult<RedisGeoCommands.GeoLocation<String>> geoResult = new GeoResult<>(
            new RedisGeoCommands.GeoLocation<>(riderId.toString(), null),
            new Distance(0.4, Metrics.KILOMETERS));
        when(geoOps.radius(eq("riders:active"), any(Circle.class), any(RedisGeoCommands.GeoRadiusCommandArgs.class)))
            .thenReturn(new GeoResults<>(List.of(geoResult)));
        when(assignmentRepository.existsByOrderId(orderId)).thenReturn(false);
        when(availabilityRepository.findByStoreIdAndIsAvailableTrueAndRiderIdIn(storeId, List.of(riderId)))
            .thenReturn(List.of(availability));
        when(riderRepository.findAllById(any())).thenReturn(List.of(rider));
        when(availabilityRepository.findAvailableByRiderIdForUpdateSkipLocked(riderId))
            .thenReturn(Optional.of(availability));

        UUID assigned = redisBackedService.assignRider(orderId, storeId, pickupLat, pickupLng);

        assertThat(assigned).isEqualTo(riderId);
        verify(availabilityRepository, never()).findEligibleNearestCandidates(
            any(), any(), anyDouble(), any(), anyInt(), anyInt(), anyInt(), any(), anyBoolean(), any(), anyInt());
        verify(outboxService).publish(
            eq("Rider"), eq(riderId.toString()), eq("RiderAssigned"), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsEntry("vehicleType", "CAR");
    }

    @Test
    @DisplayName("assignRider falls back to PostgreSQL when Redis GEORADIUS fails")
    @SuppressWarnings("unchecked")
    void assignRiderFallsBackToPostgresWhenRedisErrors() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        GeoOperations<String, String> geoOps = mock(GeoOperations.class);
        when(redisTemplate.opsForGeo()).thenReturn(geoOps);
        when(geoOps.radius(eq("riders:active"), any(Circle.class), any(RedisGeoCommands.GeoRadiusCommandArgs.class)))
            .thenThrow(new RuntimeException("Redis timeout"));

        RiderAssignmentService redisBackedService = new RiderAssignmentService(
            riderRepository, availabilityRepository, assignmentRepository,
            outboxService, properties, dispatchOptimizerClient,
            null, redisTemplate, null, Clock.systemUTC());

        UUID orderId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        BigDecimal pickupLat = new BigDecimal("12.9716");
        BigDecimal pickupLng = new BigDecimal("77.5946");

        RiderAvailability availability = new RiderAvailability();
        availability.setRiderId(riderId);
        availability.setStoreId(storeId);
        availability.setAvailable(true);

        Rider rider = new Rider();
        rider.setId(riderId);
        rider.setName("PG Fallback Rider");
        rider.setStatus(RiderStatus.ACTIVE);

        when(assignmentRepository.existsByOrderId(orderId)).thenReturn(false);
        when(availabilityRepository.findEligibleNearestCandidates(
            eq(pickupLat), eq(pickupLng), eq(5.0), eq(storeId), eq(0),
            anyInt(), anyInt(), any(), eq(false), any(), anyInt()))
            .thenReturn(List.of(availability));
        when(riderRepository.findAllById(List.of(riderId))).thenReturn(List.of(rider));
        when(availabilityRepository.findAvailableByRiderIdForUpdateSkipLocked(riderId))
            .thenReturn(Optional.of(availability));

        UUID assigned = redisBackedService.assignRider(orderId, storeId, pickupLat, pickupLng);

        assertThat(assigned).isEqualTo(riderId);
    }
}
