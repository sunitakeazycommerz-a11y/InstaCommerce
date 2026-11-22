package com.instacommerce.fulfillment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.fulfillment.config.FulfillmentProperties;
import com.instacommerce.fulfillment.domain.model.Delivery;
import com.instacommerce.fulfillment.domain.model.DeliveryStatus;
import com.instacommerce.fulfillment.domain.model.PickTask;
import com.instacommerce.fulfillment.domain.model.PickTaskStatus;
import com.instacommerce.fulfillment.domain.model.Rider;
import com.instacommerce.fulfillment.dto.request.MarkDeliveryFailedRequest;
import com.instacommerce.fulfillment.dto.response.DeliveryResponse;
import com.instacommerce.fulfillment.event.OrderStatusUpdateEvent;
import com.instacommerce.fulfillment.exception.InvalidDeliveryStateException;
import com.instacommerce.fulfillment.exception.InvalidPickTaskStateException;
import com.instacommerce.fulfillment.repository.DeliveryRepository;
import com.instacommerce.fulfillment.repository.PickTaskRepository;
import com.instacommerce.fulfillment.repository.RiderRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class DeliveryServiceTest {

    @Mock
    private DeliveryRepository deliveryRepository;

    @Mock
    private PickTaskRepository pickTaskRepository;

    @Mock
    private RiderRepository riderRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private OutboxService outboxService;

    @Captor
    private ArgumentCaptor<Map<String, Object>> payloadCaptor;

    private FulfillmentProperties properties;
    private DeliveryService deliveryService;

    @BeforeEach
    void setUp() {
        properties = new FulfillmentProperties();
        properties.getDelivery().setDefaultEtaMinutes(15);
        deliveryService = new DeliveryService(
            deliveryRepository,
            pickTaskRepository,
            riderRepository,
            eventPublisher,
            outboxService,
            properties
        );
    }

    @Test
    void assignRiderRejectsCancelledPickTask() {
        UUID orderId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        PickTask task = newPickTask(orderId, PickTaskStatus.CANCELLED);

        when(pickTaskRepository.findByOrderId(orderId)).thenReturn(Optional.of(task));
        lenient().when(riderRepository.findById(riderId)).thenReturn(Optional.of(newRider(riderId, true)));

        assertThatThrownBy(() -> deliveryService.assignRider(orderId, riderId, 12))
            .isInstanceOf(InvalidPickTaskStateException.class);

        verify(riderRepository, never()).save(any());
        verify(deliveryRepository, never()).save(any());
        verify(outboxService, never()).publish(any(), any(), any(), any());
    }

    @Test
    void assignRiderRejectsFailedDelivery() {
        UUID orderId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        PickTask task = newPickTask(orderId, PickTaskStatus.COMPLETED);
        Delivery failed = newDelivery(orderId, DeliveryStatus.FAILED, newRider(UUID.randomUUID(), true));

        when(pickTaskRepository.findByOrderId(orderId)).thenReturn(Optional.of(task));
        when(deliveryRepository.findByOrderId(orderId)).thenReturn(Optional.of(failed));
        lenient().when(riderRepository.findById(riderId)).thenReturn(Optional.of(newRider(riderId, true)));

        assertThatThrownBy(() -> deliveryService.assignRider(orderId, riderId, 12))
            .isInstanceOf(InvalidDeliveryStateException.class);

        verify(riderRepository, never()).save(any());
        verify(deliveryRepository, never()).save(any());
        verify(outboxService, never()).publish(any(), any(), any(), any());
    }

    @Test
    void assignRiderRejectsDeliveredDelivery() {
        UUID orderId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        PickTask task = newPickTask(orderId, PickTaskStatus.COMPLETED);
        Delivery delivered = newDelivery(orderId, DeliveryStatus.DELIVERED, newRider(UUID.randomUUID(), true));

        when(pickTaskRepository.findByOrderId(orderId)).thenReturn(Optional.of(task));
        when(deliveryRepository.findByOrderId(orderId)).thenReturn(Optional.of(delivered));
        lenient().when(riderRepository.findById(riderId)).thenReturn(Optional.of(newRider(riderId, true)));

        assertThatThrownBy(() -> deliveryService.assignRider(orderId, riderId, 12))
            .isInstanceOf(InvalidDeliveryStateException.class);

        verify(riderRepository, never()).save(any());
        verify(deliveryRepository, never()).save(any());
        verify(outboxService, never()).publish(any(), any(), any(), any());
    }

    @Test
    void assignRiderSucceedsForActivePickTask() {
        UUID orderId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        PickTask task = newPickTask(orderId, PickTaskStatus.COMPLETED);
        Rider rider = newRider(riderId, true);

        when(pickTaskRepository.findByOrderId(orderId)).thenReturn(Optional.of(task));
        when(deliveryRepository.findByOrderId(orderId)).thenReturn(Optional.empty());
        when(riderRepository.findById(riderId)).thenReturn(Optional.of(rider));
        when(deliveryRepository.save(any(Delivery.class))).thenAnswer(inv -> {
            Delivery saved = inv.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });

        DeliveryResponse response = deliveryService.assignRider(orderId, riderId, 10);

        assertThat(response.status()).isEqualTo(DeliveryStatus.ASSIGNED.name());
        assertThat(response.riderId()).isEqualTo(riderId);
        assertThat(rider.isAvailable()).isFalse();
        verify(outboxService).publish(eq("Fulfillment"), eq(orderId.toString()), eq("OrderDispatched"), any());
    }

    @Test
    void markDeliveredRejectsFailedDelivery() {
        UUID orderId = UUID.randomUUID();
        Delivery failed = newDelivery(orderId, DeliveryStatus.FAILED, newRider(UUID.randomUUID(), false));

        when(deliveryRepository.findByOrderId(orderId)).thenReturn(Optional.of(failed));
        lenient().when(pickTaskRepository.findByOrderId(orderId))
            .thenReturn(Optional.of(newPickTask(orderId, PickTaskStatus.COMPLETED)));

        assertThatThrownBy(() -> deliveryService.markDelivered(orderId))
            .isInstanceOf(InvalidDeliveryStateException.class);

        verify(deliveryRepository, never()).save(any());
        verify(outboxService, never()).publish(any(), any(), eq("OrderDelivered"), any());
    }

    @Test
    void markDeliveredRejectsAlreadyDeliveredDelivery() {
        UUID orderId = UUID.randomUUID();
        Delivery delivered = newDelivery(orderId, DeliveryStatus.DELIVERED, newRider(UUID.randomUUID(), true));

        when(deliveryRepository.findByOrderId(orderId)).thenReturn(Optional.of(delivered));
        lenient().when(pickTaskRepository.findByOrderId(orderId))
            .thenReturn(Optional.of(newPickTask(orderId, PickTaskStatus.COMPLETED)));

        assertThatThrownBy(() -> deliveryService.markDelivered(orderId))
            .isInstanceOf(InvalidDeliveryStateException.class);

        verify(deliveryRepository, never()).save(any());
        verify(outboxService, never()).publish(any(), any(), eq("OrderDelivered"), any());
    }

    @Test
    void markDeliveredRejectsCancelledPickTask() {
        UUID orderId = UUID.randomUUID();
        Delivery assigned = newDelivery(orderId, DeliveryStatus.ASSIGNED, newRider(UUID.randomUUID(), false));
        PickTask cancelledTask = newPickTask(orderId, PickTaskStatus.CANCELLED);

        when(deliveryRepository.findByOrderId(orderId)).thenReturn(Optional.of(assigned));
        when(pickTaskRepository.findByOrderId(orderId)).thenReturn(Optional.of(cancelledTask));

        assertThatThrownBy(() -> deliveryService.markDelivered(orderId))
            .isInstanceOf(InvalidPickTaskStateException.class);

        verify(deliveryRepository, never()).save(any());
        verify(outboxService, never()).publish(any(), any(), eq("OrderDelivered"), any());
    }

    @Test
    void verifyRiderHandoffRejectsTerminalAndCancelledStates() {
        UUID orderId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        Rider rider = newRider(riderId, false);

        Delivery failed = newDelivery(orderId, DeliveryStatus.FAILED, rider);
        failed.setDispatchOtp("123456");
        when(deliveryRepository.findByOrderId(orderId)).thenReturn(Optional.of(failed));
        lenient().when(pickTaskRepository.findByOrderId(orderId))
            .thenReturn(Optional.of(newPickTask(orderId, PickTaskStatus.COMPLETED)));

        assertThatThrownBy(() -> deliveryService.verifyRiderHandoff(orderId, riderId, "123456"))
            .isInstanceOf(InvalidDeliveryStateException.class);

        Delivery delivered = newDelivery(orderId, DeliveryStatus.DELIVERED, rider);
        delivered.setDispatchOtp("123456");
        when(deliveryRepository.findByOrderId(orderId)).thenReturn(Optional.of(delivered));

        assertThatThrownBy(() -> deliveryService.verifyRiderHandoff(orderId, riderId, "123456"))
            .isInstanceOf(InvalidDeliveryStateException.class);

        Delivery assigned = newDelivery(orderId, DeliveryStatus.ASSIGNED, rider);
        assigned.setDispatchOtp("123456");
        when(deliveryRepository.findByOrderId(orderId)).thenReturn(Optional.of(assigned));
        when(pickTaskRepository.findByOrderId(orderId)).thenReturn(Optional.of(newPickTask(orderId, PickTaskStatus.CANCELLED)));

        assertThatThrownBy(() -> deliveryService.verifyRiderHandoff(orderId, riderId, "123456"))
            .isInstanceOf(InvalidPickTaskStateException.class);

        verify(deliveryRepository, never()).save(any());
    }

    @Test
    void recordProofOfDeliveryRejectsFailedAndCancelledStates() {
        UUID orderId = UUID.randomUUID();
        Delivery failed = newDelivery(orderId, DeliveryStatus.FAILED, newRider(UUID.randomUUID(), false));
        when(deliveryRepository.findByOrderId(orderId)).thenReturn(Optional.of(failed));
        lenient().when(pickTaskRepository.findByOrderId(orderId))
            .thenReturn(Optional.of(newPickTask(orderId, PickTaskStatus.COMPLETED)));

        assertThatThrownBy(() -> deliveryService.recordProofOfDelivery(
            orderId, "https://storage.googleapis.com/pod/1.webp", "WEBP", 50_000, true))
            .isInstanceOf(InvalidDeliveryStateException.class);

        Delivery assigned = newDelivery(orderId, DeliveryStatus.ASSIGNED, newRider(UUID.randomUUID(), false));
        when(deliveryRepository.findByOrderId(orderId)).thenReturn(Optional.of(assigned));
        when(pickTaskRepository.findByOrderId(orderId)).thenReturn(Optional.of(newPickTask(orderId, PickTaskStatus.CANCELLED)));

        assertThatThrownBy(() -> deliveryService.recordProofOfDelivery(
            orderId, "https://storage.googleapis.com/pod/1.webp", "WEBP", 50_000, true))
            .isInstanceOf(InvalidPickTaskStateException.class);

        verify(deliveryRepository, never()).save(any());
    }

    @Test
    void markDeliveryFailedTransitionsToFailedReleasesRiderAndPublishesEvents() {
        UUID orderId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        Rider rider = newRider(riderId, false);
        Delivery delivery = newDelivery(orderId, DeliveryStatus.PICKED_UP, rider);

        when(deliveryRepository.findByOrderId(orderId)).thenReturn(Optional.of(delivery));
        when(deliveryRepository.save(any(Delivery.class))).thenAnswer(inv -> inv.getArgument(0));

        MarkDeliveryFailedRequest request = new MarkDeliveryFailedRequest(
            "Customer unreachable after 3 attempts",
            "CUSTOMER_UNREACHABLE",
            "Left voicemail",
            riderId
        );

        DeliveryResponse response = deliveryService.markDeliveryFailed(orderId, request, riderId, false);

        assertThat(response.status()).isEqualTo(DeliveryStatus.FAILED.name());
        assertThat(rider.isAvailable()).isTrue();
        verify(riderRepository).save(rider);
        verify(outboxService).publish(
            eq("Fulfillment"), eq(orderId.toString()), eq("DeliveryFailed"), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
            .containsEntry("orderId", orderId)
            .containsEntry("deliveryId", delivery.getId())
            .containsEntry("riderId", riderId)
            .containsEntry("reason", "Customer unreachable after 3 attempts")
            .containsEntry("failureCode", "CUSTOMER_UNREACHABLE")
            .containsEntry("notes", "Left voicemail")
            .containsKey("failedAt");
        verify(eventPublisher).publishEvent(
            new OrderStatusUpdateEvent(orderId, "FAILED", "Customer unreachable after 3 attempts"));
    }

    @Test
    void markDeliveryFailedIsIdempotentWhenAlreadyFailed() {
        UUID orderId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        Rider rider = newRider(riderId, true);
        Delivery failed = newDelivery(orderId, DeliveryStatus.FAILED, rider);

        when(deliveryRepository.findByOrderId(orderId)).thenReturn(Optional.of(failed));

        DeliveryResponse response = deliveryService.markDeliveryFailed(
            orderId, new MarkDeliveryFailedRequest("Duplicate failure report"), riderId, false);

        assertThat(response.status()).isEqualTo(DeliveryStatus.FAILED.name());
        verify(deliveryRepository, never()).save(any());
        verify(riderRepository, never()).save(any());
        verify(outboxService, never()).publish(any(), any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void markDeliveryFailedRejectsDeliveredAndUnauthorizedRider() {
        UUID orderId = UUID.randomUUID();
        UUID assignedRiderId = UUID.randomUUID();
        UUID otherRiderId = UUID.randomUUID();
        Rider rider = newRider(assignedRiderId, true);
        Delivery delivered = newDelivery(orderId, DeliveryStatus.DELIVERED, rider);

        when(deliveryRepository.findByOrderId(orderId)).thenReturn(Optional.of(delivered));

        assertThatThrownBy(() -> deliveryService.markDeliveryFailed(
            orderId, new MarkDeliveryFailedRequest("Too late"), assignedRiderId, false))
            .isInstanceOf(InvalidDeliveryStateException.class);

        Delivery inTransit = newDelivery(orderId, DeliveryStatus.PICKED_UP, rider);
        when(deliveryRepository.findByOrderId(orderId)).thenReturn(Optional.of(inTransit));

        assertThatThrownBy(() -> deliveryService.markDeliveryFailed(
            orderId, new MarkDeliveryFailedRequest("Wrong rider"), otherRiderId, false))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void markDeliveryFailedRejectsCancelledPickTask() {
        UUID orderId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();
        Rider rider = newRider(riderId, false);
        Delivery inTransit = newDelivery(orderId, DeliveryStatus.PICKED_UP, rider);
        PickTask cancelledTask = newPickTask(orderId, PickTaskStatus.CANCELLED);

        when(deliveryRepository.findByOrderId(orderId)).thenReturn(Optional.of(inTransit));
        when(pickTaskRepository.findByOrderId(orderId)).thenReturn(Optional.of(cancelledTask));

        assertThatThrownBy(() -> deliveryService.markDeliveryFailed(
            orderId, new MarkDeliveryFailedRequest("Customer unreachable"), riderId, false))
            .isInstanceOf(InvalidPickTaskStateException.class);

        verify(deliveryRepository, never()).save(any());
        verify(riderRepository, never()).save(any());
        verify(outboxService, never()).publish(any(), any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    private PickTask newPickTask(UUID orderId, PickTaskStatus status) {
        PickTask task = new PickTask();
        task.setId(UUID.randomUUID());
        task.setOrderId(orderId);
        task.setUserId(UUID.randomUUID());
        task.setStoreId(UUID.randomUUID());
        task.setStatus(status);
        return task;
    }

    private Rider newRider(UUID riderId, boolean available) {
        Rider rider = new Rider();
        rider.setId(riderId);
        rider.setName("Vikram");
        rider.setStoreId(UUID.randomUUID());
        rider.setAvailable(available);
        return rider;
    }

    private Delivery newDelivery(UUID orderId, DeliveryStatus status, Rider rider) {
        Delivery delivery = new Delivery();
        delivery.setId(UUID.randomUUID());
        delivery.setOrderId(orderId);
        delivery.setRider(rider);
        delivery.setStatus(status);
        return delivery;
    }
}
