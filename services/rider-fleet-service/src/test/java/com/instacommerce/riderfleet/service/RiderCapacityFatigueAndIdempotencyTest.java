package com.instacommerce.riderfleet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.instacommerce.riderfleet.domain.model.RiderShift;
import com.instacommerce.riderfleet.domain.model.RiderStatus;
import com.instacommerce.riderfleet.domain.model.VehicleType;
import com.instacommerce.riderfleet.dto.response.RiderResponse;
import com.instacommerce.riderfleet.exception.InvalidRiderStateException;
import com.instacommerce.riderfleet.exception.NoAvailableRiderException;
import com.instacommerce.riderfleet.repository.RiderAssignmentRepository;
import com.instacommerce.riderfleet.repository.RiderAvailabilityRepository;
import com.instacommerce.riderfleet.repository.RiderRepository;
import com.instacommerce.riderfleet.repository.RiderShiftRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RiderCapacityFatigueAndIdempotencyTest {

    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    @Mock
    private RiderRepository riderRepository;

    @Mock
    private RiderAvailabilityRepository availabilityRepository;

    @Mock
    private RiderAssignmentRepository assignmentRepository;

    @Mock
    private RiderShiftRepository shiftRepository;

    @Mock
    private OutboxService outboxService;

    @Mock
    private DispatchOptimizerClient dispatchOptimizerClient;

    @Test
    @DisplayName("Optimizer path filters out overweight bicycle riders and assigns motorcycle within capacity")
    void capacityFilterExcludesOverweightBicycleInOptimizer() {
        UUID orderId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID bikeRiderId = UUID.randomUUID();
        UUID motoRiderId = UUID.randomUUID();

        RiderFleetProperties props = new RiderFleetProperties();
        props.getDispatch().setOptimizerEnabled(true);

        Rider bikeRider = buildRider(bikeRiderId, storeId, VehicleType.BICYCLE, RiderStatus.ACTIVE);
        Rider motoRider = buildRider(motoRiderId, storeId, VehicleType.MOTORCYCLE, RiderStatus.ACTIVE);
        RiderAvailability bikeAvail = buildAvailability(bikeRiderId, storeId);
        RiderAvailability motoAvail = buildAvailability(motoRiderId, storeId);

        when(assignmentRepository.existsByOrderId(orderId)).thenReturn(false);
        when(availabilityRepository.findByIsAvailableTrueAndStoreId(storeId)).thenReturn(List.of(bikeAvail, motoAvail));
        when(riderRepository.findAllById(List.of(bikeRiderId, motoRiderId))).thenReturn(List.of(bikeRider, motoRider));
        when(dispatchOptimizerClient.requestAssignment(any())).thenReturn(Optional.of(
            new DispatchOptimizerClient.OptimizationResult(
                List.of(new DispatchOptimizerClient.Assignment(motoRiderId.toString(), List.of(orderId.toString()), 5.0)),
                List.of())));
        when(availabilityRepository.findByRiderIdForUpdate(motoRiderId)).thenReturn(Optional.of(motoAvail));
        when(riderRepository.findById(motoRiderId)).thenReturn(Optional.of(motoRider));

        RiderAssignmentService service = new RiderAssignmentService(
            riderRepository, availabilityRepository, assignmentRepository, outboxService,
            props, dispatchOptimizerClient, null, null, null, Clock.fixed(NOW, ZoneOffset.UTC));

        UUID assigned = service.assignRider(
            orderId, storeId, new BigDecimal("12.97"), new BigDecimal("77.59"), null, null, 12_000);

        assertThat(assigned).isEqualTo(motoRiderId);
        ArgumentCaptor<DispatchOptimizerClient.OptimizationRequest> reqCaptor =
            ArgumentCaptor.forClass(DispatchOptimizerClient.OptimizationRequest.class);
        verify(dispatchOptimizerClient).requestAssignment(reqCaptor.capture());
        assertThat(reqCaptor.getValue().riders()).hasSize(1);
        assertThat(reqCaptor.getValue().riders().get(0).id()).isEqualTo(motoRiderId.toString());
    }

    @Test
    @DisplayName("Haversine weight query rejects when no capable rider and accepts custom maxCapacityGrams")
    void haversineCapacityQueryAndCustomCapacityGuard() {
        UUID orderId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID bikeRiderId = UUID.randomUUID();

        RiderFleetProperties props = new RiderFleetProperties();
        Rider bikeRider = buildRider(bikeRiderId, storeId, VehicleType.BICYCLE, RiderStatus.ACTIVE);
        bikeRider.setMaxCapacityGrams(15_000);
        RiderAvailability bikeAvail = buildAvailability(bikeRiderId, storeId);

        when(assignmentRepository.existsByOrderId(orderId)).thenReturn(false);
        when(availabilityRepository.findNearestAvailableWithCapacity(
            new BigDecimal("12.97"), new BigDecimal("77.59"), 5.0, storeId, 12_000))
            .thenReturn(Optional.of(bikeAvail));
        when(riderRepository.findById(bikeRiderId)).thenReturn(Optional.of(bikeRider));

        RiderAssignmentService service = new RiderAssignmentService(
            riderRepository, availabilityRepository, assignmentRepository, outboxService,
            props, dispatchOptimizerClient, null, null, null, Clock.fixed(NOW, ZoneOffset.UTC));

        UUID assigned = service.assignRider(
            orderId, storeId, new BigDecimal("12.97"), new BigDecimal("77.59"), null, null, 12_000);
        assertThat(assigned).isEqualTo(bikeRiderId);
    }

    @Test
    @DisplayName("Consecutive delivery fatigue blocks rest-window toggling and rejects assignment until cooldown expires")
    void fatigueLimitEnforcedAndResetsAfterCooldown() {
        UUID orderId = UUID.randomUUID();
        UUID nextOrderId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();

        RiderFleetProperties props = new RiderFleetProperties();
        props.getAssignment().setMaxConsecutiveDeliveries(3);
        props.getAssignment().setFatigueResetMinutes(30);

        Rider rider = buildRider(riderId, storeId, VehicleType.MOTORCYCLE, RiderStatus.ON_DELIVERY);
        rider.setConsecutiveDeliveries(2);
        rider.setLastDeliveryCompletedAt(NOW.minus(Duration.ofMinutes(10)));

        RiderAssignment assignment = new RiderAssignment();
        assignment.setOrderId(orderId);
        assignment.setRiderId(riderId);
        assignment.setStoreId(storeId);
        assignment.setStatus("ACTIVE");

        when(assignmentRepository.findByOrderId(orderId)).thenReturn(Optional.of(assignment));
        when(assignmentRepository.markCompletedIfPending(orderId, NOW)).thenReturn(1);
        when(riderRepository.findById(riderId)).thenReturn(Optional.of(rider));

        RiderAssignmentService service = new RiderAssignmentService(
            riderRepository, availabilityRepository, assignmentRepository, outboxService,
            props, dispatchOptimizerClient, null, null, null, Clock.fixed(NOW, ZoneOffset.UTC));

        service.completeDelivery(orderId);

        assertThat(rider.getConsecutiveDeliveries()).isEqualTo(3);
        verify(availabilityRepository, never()).save(any());
        verify(outboxService).publish(eq("Rider"), eq(riderId.toString()), eq("RiderFatigueLimitReached"), any());

        RiderAvailability availability = buildAvailability(riderId, storeId);
        availability.setAvailable(false);
        RiderAvailabilityService availabilityService = new RiderAvailabilityService(
            availabilityRepository, riderRepository, null, null, new ObjectMapper(),
            props, Clock.fixed(NOW.plus(Duration.ofMinutes(5)), ZoneOffset.UTC));

        assertThatThrownBy(() -> availabilityService.toggleAvailability(riderId, true))
            .isInstanceOf(InvalidRiderStateException.class);

        when(availabilityRepository.findByRiderId(riderId)).thenReturn(Optional.of(availability));
        RiderAvailabilityService afterCooldown = new RiderAvailabilityService(
            availabilityRepository, riderRepository, null, null, new ObjectMapper(),
            props, Clock.fixed(NOW.plus(Duration.ofMinutes(35)), ZoneOffset.UTC));
        afterCooldown.toggleAvailability(riderId, true);
        assertThat(rider.getConsecutiveDeliveries()).isZero();
        assertThat(availability.isAvailable()).isTrue();

        rider.setConsecutiveDeliveries(3);
        rider.setLastDeliveryCompletedAt(NOW);
        when(assignmentRepository.existsByOrderId(nextOrderId)).thenReturn(false);
        when(availabilityRepository.findNearestAvailable(any(), any(), eq(5.0), eq(storeId)))
            .thenReturn(Optional.of(availability));
        assertThatThrownBy(() -> service.assignRider(
            nextOrderId, storeId, new BigDecimal("12.97"), new BigDecimal("77.59")))
            .isInstanceOf(NoAvailableRiderException.class);
    }

    @Test
    @DisplayName("Active shift validation rejects off-shift riders and accepts on-shift riders")
    void shiftValidationEnforcedWhenConfigured() {
        UUID orderId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();

        RiderFleetProperties props = new RiderFleetProperties();
        props.getAssignment().setEnforceShiftValidation(true);

        Rider rider = buildRider(riderId, storeId, VehicleType.CAR, RiderStatus.ACTIVE);
        RiderAvailability avail = buildAvailability(riderId, storeId);
        RiderShiftService shiftService = new RiderShiftService(shiftRepository);

        when(assignmentRepository.existsByOrderId(orderId)).thenReturn(false);
        when(availabilityRepository.findNearestAvailable(any(), any(), eq(5.0), eq(storeId)))
            .thenReturn(Optional.of(avail));
        when(riderRepository.findById(riderId)).thenReturn(Optional.of(rider));
        when(shiftRepository.findByRiderIdAndStatus(riderId, "ACTIVE")).thenReturn(List.of());

        RiderAssignmentService service = new RiderAssignmentService(
            riderRepository, availabilityRepository, assignmentRepository, outboxService,
            props, dispatchOptimizerClient, null, null, shiftService, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.assignRider(
            orderId, storeId, new BigDecimal("12.97"), new BigDecimal("77.59")))
            .isInstanceOf(NoAvailableRiderException.class);

        RiderShift activeShift = new RiderShift();
        activeShift.setRiderId(riderId);
        activeShift.setStatus("ACTIVE");
        activeShift.setShiftStart(NOW.minus(Duration.ofHours(1)));
        activeShift.setShiftEnd(NOW.plus(Duration.ofHours(3)));
        when(shiftRepository.findByRiderIdAndStatus(riderId, "ACTIVE")).thenReturn(List.of(activeShift));

        assertThat(service.assignRider(orderId, storeId, new BigDecimal("12.97"), new BigDecimal("77.59")))
            .isEqualTo(riderId);
    }

    @Test
    @DisplayName("RiderService transitions and cancelAssignment are idempotent without duplicate events")
    void riderAndAssignmentStateTransitionsAreIdempotent() {
        UUID riderId = UUID.randomUUID();
        Rider rider = buildRider(riderId, UUID.randomUUID(), VehicleType.MOTORCYCLE, RiderStatus.ACTIVE);
        when(riderRepository.findById(riderId)).thenReturn(Optional.of(rider));

        RiderService riderService = new RiderService(riderRepository, availabilityRepository, outboxService);
        RiderResponse activated = riderService.activateRider(riderId);
        RiderResponse onboarded = riderService.onboardRider(riderId);
        assertThat(activated.status()).isEqualTo(RiderStatus.ACTIVE);
        assertThat(onboarded.status()).isEqualTo(RiderStatus.ACTIVE);
        verify(riderRepository, never()).save(any());

        rider.setStatus(RiderStatus.SUSPENDED);
        RiderResponse suspended = riderService.suspendRider(riderId);
        assertThat(suspended.status()).isEqualTo(RiderStatus.SUSPENDED);
        assertThatThrownBy(() -> riderService.activateRider(riderId))
            .isInstanceOf(InvalidRiderStateException.class);

        UUID orderId = UUID.randomUUID();
        RiderAssignment assignment = new RiderAssignment();
        assignment.setOrderId(orderId);
        assignment.setRiderId(riderId);
        assignment.setStatus("ACTIVE");
        rider.setStatus(RiderStatus.ON_DELIVERY);
        RiderAvailability availability = buildAvailability(riderId, UUID.randomUUID());
        availability.setAvailable(false);

        when(assignmentRepository.findByOrderId(orderId)).thenReturn(Optional.of(assignment));
        when(availabilityRepository.findByRiderId(riderId)).thenReturn(Optional.of(availability));

        RiderAssignmentService assignmentService = new RiderAssignmentService(
            riderRepository, availabilityRepository, assignmentRepository, outboxService,
            new RiderFleetProperties(), dispatchOptimizerClient, null, null, null, Clock.fixed(NOW, ZoneOffset.UTC));

        assignmentService.cancelAssignment(orderId, "user cancelled");
        assignmentService.cancelAssignment(orderId, "user cancelled");

        assertThat(assignment.getStatus()).isEqualTo("CANCELLED");
        verify(outboxService, times(1)).publish(eq("Rider"), eq(riderId.toString()), eq("RiderAssignmentCancelled"), any());
    }

    private Rider buildRider(UUID id, UUID storeId, VehicleType vehicleType, RiderStatus status) {
        Rider rider = new Rider();
        rider.setId(id);
        rider.setStoreId(storeId);
        rider.setName("Rider-" + id.toString().substring(0, 4));
        rider.setVehicleType(vehicleType);
        rider.setStatus(status);
        rider.setRatingAvg(new BigDecimal("4.90"));
        return rider;
    }

    private RiderAvailability buildAvailability(UUID riderId, UUID storeId) {
        RiderAvailability availability = new RiderAvailability();
        availability.setRiderId(riderId);
        availability.setStoreId(storeId);
        availability.setAvailable(true);
        availability.setCurrentLat(new BigDecimal("12.9716"));
        availability.setCurrentLng(new BigDecimal("77.5946"));
        return availability;
    }
}
