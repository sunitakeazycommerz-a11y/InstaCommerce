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
import com.instacommerce.riderfleet.repository.RiderAssignmentRepository;
import com.instacommerce.riderfleet.repository.RiderAvailabilityRepository;
import com.instacommerce.riderfleet.repository.RiderRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RiderAssignmentServiceTest {

    @Mock private RiderRepository riderRepository;
    @Mock private RiderAvailabilityRepository availabilityRepository;
    @Mock private RiderAssignmentRepository assignmentRepository;
    @Mock private OutboxService outboxService;
    @Mock private DispatchOptimizerClient dispatchOptimizerClient;

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
            dispatchOptimizerClient
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
}
