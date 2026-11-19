package com.instacommerce.fulfillment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.instacommerce.fulfillment.client.InventoryClient;
import com.instacommerce.fulfillment.client.PaymentClient;
import com.instacommerce.fulfillment.client.WarehouseClient;
import com.instacommerce.fulfillment.config.FulfillmentProperties;
import com.instacommerce.fulfillment.domain.model.Delivery;
import com.instacommerce.fulfillment.domain.model.DeliveryStatus;
import com.instacommerce.fulfillment.domain.model.PickBatch;
import com.instacommerce.fulfillment.domain.model.PickItem;
import com.instacommerce.fulfillment.domain.model.PickItemStatus;
import com.instacommerce.fulfillment.domain.model.PickTask;
import com.instacommerce.fulfillment.domain.model.PickTaskStatus;
import com.instacommerce.fulfillment.domain.model.Rider;
import com.instacommerce.fulfillment.dto.request.MarkItemPickedRequest;
import com.instacommerce.fulfillment.event.ItemSubstitutedEvent;
import com.instacommerce.fulfillment.event.MissingItemEvent;
import com.instacommerce.fulfillment.event.MissingItemEventListener;
import com.instacommerce.fulfillment.repository.DeliveryRepository;
import com.instacommerce.fulfillment.repository.PickBatchRepository;
import com.instacommerce.fulfillment.repository.PickItemRepository;
import com.instacommerce.fulfillment.repository.PickTaskRepository;
import com.instacommerce.fulfillment.repository.RiderRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class FulfillmentRemediationTest {

    @Mock
    private InventoryClient inventoryClient;

    @Mock
    private PaymentClient paymentClient;

    @Mock
    private OutboxService outboxService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private PickTaskRepository pickTaskRepository;

    @Mock
    private PickItemRepository pickItemRepository;

    @Mock
    private PickBatchRepository pickBatchRepository;

    @Mock
    private DeliveryRepository deliveryRepository;

    @Mock
    private RiderRepository riderRepository;

    @Mock
    private AisleRoutingOptimizer aisleRoutingOptimizer;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private WarehouseClient warehouseClient;

    @Captor
    private ArgumentCaptor<Map<String, Object>> payloadCaptor;

    @Test
    @DisplayName("SubstitutionService defers inventory HTTP calls to AFTER_COMMIT and preserves substituteProductId on shortfall")
    void substitutionAdjustsSkusAndEmitsItemSubstituted() {
        UUID orderId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID originalProductId = UUID.randomUUID();
        UUID replacementProductId = UUID.randomUUID();

        PickTask task = new PickTask();
        task.setId(UUID.randomUUID());
        task.setOrderId(orderId);
        task.setStoreId(storeId);
        task.setPaymentId(paymentId);

        PickItem item = new PickItem();
        item.setProductId(originalProductId);
        item.setQuantity(3);
        item.setPickedQty(2);
        item.setLineTotalCents(3000L);
        item.setStatus(PickItemStatus.SUBSTITUTED);
        item.setSubstituteProductId(replacementProductId);

        SubstitutionService service = new SubstitutionService(
            inventoryClient, paymentClient, outboxService, eventPublisher);
        service.handleMissingItem(task, item, 1);

        verifyNoInteractions(inventoryClient, paymentClient);
        verify(outboxService).publish(
            eq("Fulfillment"), eq(orderId.toString()), eq("ItemSubstituted"), any());
        verify(outboxService).publish(
            eq("Fulfillment"), eq(orderId.toString()), eq("OrderModified"), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsEntry("missingQty", 1);

        ArgumentCaptor<Object> eventsCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, Mockito.times(2)).publishEvent(eventsCaptor.capture());

        ItemSubstitutedEvent substitutedEvent = eventsCaptor.getAllValues().stream()
            .filter(ItemSubstitutedEvent.class::isInstance)
            .map(ItemSubstitutedEvent.class::cast)
            .findFirst()
            .orElseThrow();
        MissingItemEvent missingEvent = eventsCaptor.getAllValues().stream()
            .filter(MissingItemEvent.class::isInstance)
            .map(MissingItemEvent.class::cast)
            .findFirst()
            .orElseThrow();

        assertThat(missingEvent.substituteProductId()).isEqualTo(replacementProductId);
        assertThat(missingEvent.substitutedQty()).isEqualTo(2);

        MissingItemEventListener listener = new MissingItemEventListener(service);
        listener.handleItemSubstituted(substitutedEvent);
        listener.handleMissingItem(missingEvent);

        verify(inventoryClient).releaseStock(
            eq(originalProductId), eq(storeId), eq(2), eq("FULFILLMENT_SUBSTITUTED_ORIGINAL_RELEASE"), any());
        verify(inventoryClient).reserveOrDeductStock(
            eq(replacementProductId), eq(storeId), eq(2), eq("FULFILLMENT_SUBSTITUTION_REPLACEMENT"), any());
        verify(paymentClient).refund(eq(paymentId), eq(1000L), eq("Item missing"), any());
    }

    @Test
    @DisplayName("SubstitutionService supports $0 orders with null paymentId without throwing")
    void zeroDollarOrderSkipsRefundAndStillReleasesMissingStock() {
        UUID orderId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        PickTask task = new PickTask();
        task.setId(UUID.randomUUID());
        task.setOrderId(orderId);
        task.setStoreId(storeId);
        task.setPaymentId(null);

        PickItem item = new PickItem();
        item.setProductId(productId);
        item.setQuantity(2);
        item.setPickedQty(0);
        item.setLineTotalCents(0L);
        item.setStatus(PickItemStatus.MISSING);

        SubstitutionService service = new SubstitutionService(
            inventoryClient, paymentClient, outboxService, eventPublisher);
        service.handleMissingItem(task, item, 2);

        verify(outboxService).publish(
            eq("Fulfillment"), eq(orderId.toString()), eq("OrderModified"), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
            .containsEntry("missingQty", 2)
            .containsEntry("refundCents", 0L);

        ArgumentCaptor<MissingItemEvent> eventCaptor = ArgumentCaptor.forClass(MissingItemEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());

        MissingItemEventListener listener = new MissingItemEventListener(service);
        listener.handleMissingItem(eventCaptor.getValue());

        verify(inventoryClient).reserveOrDeductStock(
            eq(productId), eq(storeId), eq(2), eq("FULFILLMENT_MISSING"), any());
        verifyNoInteractions(paymentClient);
    }

    @Test
    @DisplayName("PickService includes SUBSTITUTED shortfall in missingQty refund calculation")
    void pickServiceCalculatesSubstitutedShortfall() {
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID subProductId = UUID.randomUUID();
        PickTask task = new PickTask();
        task.setId(UUID.randomUUID());
        task.setOrderId(orderId);
        task.setStoreId(UUID.randomUUID());
        task.setPaymentId(UUID.randomUUID());
        task.setStatus(PickTaskStatus.IN_PROGRESS);

        PickItem item = new PickItem();
        item.setId(UUID.randomUUID());
        item.setPickTask(task);
        item.setProductId(productId);
        item.setQuantity(4);
        item.setPickedQty(0);
        item.setLineTotalCents(4000L);
        item.setStatus(PickItemStatus.PENDING);

        SubstitutionService subService = Mockito.mock(SubstitutionService.class);
        when(pickTaskRepository.findByOrderIdForUpdate(orderId)).thenReturn(Optional.of(task));
        when(pickItemRepository.findByPickTask_OrderIdAndProductId(orderId, productId)).thenReturn(Optional.of(item));
        when(pickItemRepository.countByPickTask_IdAndStatus(task.getId(), PickItemStatus.PENDING)).thenReturn(1L);

        PickService pickService = new PickService(
            pickTaskRepository, pickItemRepository, subService, outboxService, eventPublisher,
            warehouseClient, aisleRoutingOptimizer, auditLogService, new SimpleMeterRegistry());

        pickService.markItem(orderId, productId,
            new MarkItemPickedRequest(PickItemStatus.SUBSTITUTED, 3, subProductId, "sub"), UUID.randomUUID());
        verify(subService).handleMissingItem(task, item, 1);
    }

    @Test
    @DisplayName("BatchPickingService completeBatch triggers PickService onBatchCompleted to emit OrderPacked")
    void batchCompleteEmitsOrderPacked() {
        UUID batchId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        PickBatch batch = new PickBatch();
        batch.setId(batchId);
        batch.setStoreId(UUID.randomUUID());

        PickTask task = new PickTask();
        task.setId(UUID.randomUUID());
        task.setOrderId(orderId);
        task.setStatus(PickTaskStatus.IN_PROGRESS);

        PickService mockPickService = Mockito.mock(PickService.class);
        when(pickBatchRepository.findByIdForUpdate(batchId)).thenReturn(Optional.of(batch));
        when(pickBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        when(pickBatchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pickTaskRepository.findByBatchId(batchId)).thenReturn(List.of(task));
        when(pickItemRepository.findByPickTask_IdIn(List.of(task.getId()))).thenReturn(List.of());
        when(aisleRoutingOptimizer.optimizePickPath(any())).thenReturn(List.of());

        BatchPickingService batchService = new BatchPickingService(
            pickBatchRepository, pickTaskRepository, pickItemRepository,
            aisleRoutingOptimizer, auditLogService, mockPickService);
        batchService.completeBatch(batchId);

        verify(mockPickService).onBatchCompleted(task);
    }

    @Test
    @DisplayName("DeliveryService omits dispatchOtp from OrderDispatched outbox and enforces rider BOLA")
    void deliveryOmitsOtpAndEnforcesRiderBola() {
        UUID orderId = UUID.randomUUID();
        UUID assignedRiderId = UUID.randomUUID();
        UUID otherRiderId = UUID.randomUUID();

        Rider rider = new Rider();
        rider.setId(assignedRiderId);
        rider.setName("Asha");
        rider.setStoreId(UUID.randomUUID());
        rider.setAvailable(true);

        PickTask task = new PickTask();
        task.setOrderId(orderId);
        task.setUserId(UUID.randomUUID());
        task.setStoreId(rider.getStoreId());
        task.setStatus(PickTaskStatus.COMPLETED);

        Delivery delivery = new Delivery();
        delivery.setId(UUID.randomUUID());
        delivery.setOrderId(orderId);
        delivery.setRider(rider);
        delivery.setStatus(DeliveryStatus.PICKED_UP);

        when(pickTaskRepository.findByOrderId(orderId)).thenReturn(Optional.of(task));
        when(deliveryRepository.findByOrderId(orderId)).thenReturn(Optional.empty()).thenReturn(Optional.of(delivery));
        when(riderRepository.findById(assignedRiderId)).thenReturn(Optional.of(rider));
        when(deliveryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DeliveryService service = new DeliveryService(
            deliveryRepository, pickTaskRepository, riderRepository,
            eventPublisher, outboxService, new FulfillmentProperties());

        service.assignRider(orderId, assignedRiderId, 12);
        verify(outboxService).publish(eq("Fulfillment"), eq(orderId.toString()),
            eq("OrderDispatched"), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).doesNotContainKey("dispatchOtp");

        assertThatThrownBy(() -> service.recordProofOfDelivery(
            orderId, "https://cdn.example/pod.webp", "WEBP", 1024, true, otherRiderId, false))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.markDelivered(orderId, otherRiderId, false))
            .isInstanceOf(AccessDeniedException.class);
    }
}
