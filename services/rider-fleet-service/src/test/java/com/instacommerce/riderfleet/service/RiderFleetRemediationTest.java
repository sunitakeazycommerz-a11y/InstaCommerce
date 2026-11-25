package com.instacommerce.riderfleet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
class RiderFleetRemediationTest {

    @Mock
    private RiderRepository riderRepository;

    @Mock
    private RiderAvailabilityRepository availabilityRepository;

    @Mock
    private RiderAssignmentRepository assignmentRepository;

    @Mock
    private OutboxService outboxService;

    @Mock
    private DispatchOptimizerClient dispatchOptimizerClient;

    @Mock
    private RiderEarningsService riderEarningsService;

    @Captor
    private ArgumentCaptor<DispatchOptimizerClient.OptimizationRequest> optCaptor;

    @Test
    @DisplayName("updateLocation writes strictly to Redis and Kafka without PostgreSQL reads or writes")
    void updateLocationWritesRedisAndKafkaWithoutDatabaseAccess() {
        UUID riderId = UUID.randomUUID();
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        GeoOperations<String, String> geoOps = mock();
        HashOperations<String, Object, Object> hashOps = mock();
        when(redisTemplate.opsForGeo()).thenReturn(geoOps);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);

        RiderAvailabilityService service = new RiderAvailabilityService(
            availabilityRepository,
            riderRepository,
            redisTemplate,
            (KafkaTemplate<String, String>) null,
            new ObjectMapper());
        BigDecimal lat = new BigDecimal("12.9716");
        BigDecimal lng = new BigDecimal("77.5946");
        service.updateLocation(riderId, lat, lng);

        verify(hashOps).putAll(eq("rider:location:" + riderId), any());
        verify(availabilityRepository, never()).findByRiderId(any());
        verify(availabilityRepository, never()).save(any());
    }

    @Test
    @DisplayName("completeDelivery increments totalDeliveries, records earnings, and blocks suspended riders")
    void completeDeliveryUpdatesStatsEarningsAndRespectsSuspendedGuard() {
        UUID orderId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        RiderAssignment assignment = new RiderAssignment();
        assignment.setOrderId(orderId);
        assignment.setRiderId(riderId);
        assignment.setStoreId(UUID.randomUUID());

        Rider activeRider = new Rider();
        activeRider.setId(riderId);
        activeRider.setStatus(RiderStatus.ON_DELIVERY);
        activeRider.setTotalDeliveries(4);
        RiderAvailability availability = new RiderAvailability();
        availability.setRiderId(riderId);

        when(assignmentRepository.findByOrderId(orderId)).thenReturn(Optional.of(assignment));
        when(assignmentRepository.markCompletedIfPending(eq(orderId), any())).thenReturn(1).thenReturn(0);
        when(riderRepository.findById(riderId)).thenReturn(Optional.of(activeRider));
        when(availabilityRepository.findByRiderId(riderId)).thenReturn(Optional.of(availability));

        RiderAssignmentService service = new RiderAssignmentService(
            riderRepository, availabilityRepository, assignmentRepository,
            outboxService, new RiderFleetProperties(), dispatchOptimizerClient,
            riderEarningsService, null, null, Clock.systemUTC());
        service.completeDelivery(orderId);
        // Redelivery of same Kafka completion event must be idempotent
        service.completeDelivery(orderId);

        assertThat(activeRider.getTotalDeliveries()).isEqualTo(5);
        assertThat(activeRider.getStatus()).isEqualTo(RiderStatus.ACTIVE);
        assertThat(availability.isAvailable()).isTrue();
        verify(riderEarningsService, times(1)).recordDeliveryEarning(
            eq(orderId), eq(activeRider), eq(null), eq(null), eq(0L), any());

        UUID suspendedOrderId = UUID.randomUUID();
        UUID suspendedRiderId = UUID.randomUUID();
        RiderAssignment suspendedAssignment = new RiderAssignment();
        suspendedAssignment.setOrderId(suspendedOrderId);
        suspendedAssignment.setRiderId(suspendedRiderId);
        Rider suspended = new Rider();
        suspended.setId(suspendedRiderId);
        suspended.setStatus(RiderStatus.SUSPENDED);
        suspended.setTotalDeliveries(10);

        when(assignmentRepository.findByOrderId(suspendedOrderId)).thenReturn(Optional.of(suspendedAssignment));
        when(assignmentRepository.markCompletedIfPending(eq(suspendedOrderId), any())).thenReturn(1);
        when(riderRepository.findById(suspendedRiderId)).thenReturn(Optional.of(suspended));

        service.completeDelivery(suspendedOrderId);
        assertThat(suspended.getTotalDeliveries()).isEqualTo(10);
        verify(availabilityRepository, never()).findByRiderId(suspendedRiderId);
    }

    @Test
    @DisplayName("assignRider prefers fresh Redis coordinates over stale DB coordinates for optimizer")
    void optimizerPrefersRedisCoordinatesOverDbFallback() {
        UUID orderId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();

        RiderFleetProperties props = new RiderFleetProperties();
        props.getDispatch().setOptimizerEnabled(true);

        RiderAvailability availability = new RiderAvailability();
        availability.setRiderId(riderId);
        availability.setStoreId(storeId);
        availability.setAvailable(true);
        availability.setCurrentLat(new BigDecimal("12.9000"));
        availability.setCurrentLng(new BigDecimal("77.5000"));

        Rider rider = new Rider();
        rider.setId(riderId);
        rider.setStatus(RiderStatus.ACTIVE);
        rider.setVehicleType(VehicleType.MOTORCYCLE);

        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOps = mock();
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.get("rider:location:" + riderId, "lat")).thenReturn("12.9812");
        when(hashOps.get("rider:location:" + riderId, "lng")).thenReturn("77.6123");

        when(assignmentRepository.existsByOrderId(orderId)).thenReturn(false);
        when(availabilityRepository.findByIsAvailableTrueAndStoreId(storeId)).thenReturn(List.of(availability));
        when(riderRepository.findAllById(List.of(riderId))).thenReturn(List.of(rider));
        when(dispatchOptimizerClient.requestAssignment(any())).thenReturn(Optional.of(
            new DispatchOptimizerClient.OptimizationResult(
                List.of(new DispatchOptimizerClient.Assignment(riderId.toString(), List.of(orderId.toString()), 6.0)),
                List.of())));
        when(availabilityRepository.findAvailableByRiderIdForUpdateSkipLocked(riderId)).thenReturn(Optional.of(availability));
        when(riderRepository.findById(riderId)).thenReturn(Optional.of(rider));

        RiderAssignmentService service = new RiderAssignmentService(
            riderRepository, availabilityRepository, assignmentRepository,
            outboxService, props, dispatchOptimizerClient, riderEarningsService,
            redisTemplate, null, Clock.systemUTC());
        service.assignRider(orderId, storeId, new BigDecimal("12.97"), new BigDecimal("77.59"));

        verify(dispatchOptimizerClient).requestAssignment(optCaptor.capture());
        var state = optCaptor.getValue().riders().get(0);
        assertThat(state.position().lat()).isEqualTo(12.9812);
        assertThat(state.position().lng()).isEqualTo(77.6123);
    }

    @Test
    @DisplayName("assignRider populates totalDeliveries, avgRating, and telemetry fields in DispatchOptimizerClient")
    void optimizerRequestPopulatesRiderTelemetry() {
        UUID orderId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();

        RiderFleetProperties props = new RiderFleetProperties();
        props.getDispatch().setOptimizerEnabled(true);

        RiderAvailability availability = new RiderAvailability();
        availability.setRiderId(riderId);
        availability.setStoreId(storeId);
        availability.setAvailable(true);
        availability.setCurrentLat(new BigDecimal("12.9716"));
        availability.setCurrentLng(new BigDecimal("77.5946"));

        Rider rider = new Rider();
        rider.setId(riderId);
        rider.setStatus(RiderStatus.ACTIVE);
        rider.setVehicleType(VehicleType.MOTORCYCLE);
        rider.setTotalDeliveries(75);
        rider.setRatingAvg(new BigDecimal("4.85"));

        when(assignmentRepository.existsByOrderId(orderId)).thenReturn(false);
        when(availabilityRepository.findByIsAvailableTrueAndStoreId(storeId)).thenReturn(List.of(availability));
        when(riderRepository.findAllById(List.of(riderId))).thenReturn(List.of(rider));
        when(dispatchOptimizerClient.requestAssignment(any())).thenReturn(Optional.of(
            new DispatchOptimizerClient.OptimizationResult(
                List.of(new DispatchOptimizerClient.Assignment(riderId.toString(), List.of(orderId.toString()), 8.0)),
                List.of())));
        when(availabilityRepository.findAvailableByRiderIdForUpdateSkipLocked(riderId)).thenReturn(Optional.of(availability));
        when(riderRepository.findById(riderId)).thenReturn(Optional.of(rider));

        RiderAssignmentService service = new RiderAssignmentService(
            riderRepository, availabilityRepository, assignmentRepository,
            outboxService, props, dispatchOptimizerClient, riderEarningsService,
            null, null, Clock.systemUTC());
        service.assignRider(orderId, storeId, new BigDecimal("12.97"), new BigDecimal("77.59"));

        verify(dispatchOptimizerClient).requestAssignment(optCaptor.capture());
        var state = optCaptor.getValue().riders().get(0);
        assertThat(state.totalDeliveries()).isEqualTo(75);
        assertThat(state.avgRating()).isEqualTo(4.85);
        assertThat(state.avgSpeedKmh()).isEqualTo(22.0);
        assertThat(state.batteryPercent()).isEqualTo(100.0);
        assertThat(state.zone()).isEqualTo(storeId.toString());
    }
}
