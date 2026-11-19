package com.instacommerce.fulfillment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.fulfillment.client.StoreCoordinates;
import com.instacommerce.fulfillment.client.WarehouseClient;
import com.instacommerce.fulfillment.config.FulfillmentProperties;
import com.instacommerce.fulfillment.consumer.OrderPlacedItem;
import com.instacommerce.fulfillment.consumer.OrderPlacedPayload;
import com.instacommerce.fulfillment.domain.model.Delivery;
import com.instacommerce.fulfillment.domain.model.PickBatch;
import com.instacommerce.fulfillment.domain.model.PickItem;
import com.instacommerce.fulfillment.domain.model.PickItemStatus;
import com.instacommerce.fulfillment.domain.model.PickTask;
import com.instacommerce.fulfillment.domain.model.PickTaskStatus;
import com.instacommerce.fulfillment.domain.model.Rider;
import com.instacommerce.fulfillment.dto.request.MarkItemPickedRequest;
import com.instacommerce.fulfillment.dto.response.BatchPickResponse;
import com.instacommerce.fulfillment.dto.response.DeliveryResponse;
import com.instacommerce.fulfillment.repository.DeliveryRepository;
import com.instacommerce.fulfillment.repository.PickBatchRepository;
import com.instacommerce.fulfillment.repository.PickItemRepository;
import com.instacommerce.fulfillment.repository.PickTaskRepository;
import com.instacommerce.fulfillment.repository.RiderRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class FulfillmentPackagingAndIdempotencyTest {

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
    private SubstitutionService substitutionService;

    @Mock
    private OutboxService outboxService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private WarehouseClient warehouseClient;

    @Mock
    private AuditLogService auditLogService;

    private PickService pickService;
    private DeliveryService deliveryService;

    @BeforeEach
    void setUp() {
        pickService = new PickService(
            pickTaskRepository,
            pickItemRepository,
            substitutionService,
            outboxService,
            eventPublisher,
            warehouseClient,
            new AisleRoutingOptimizer(),
            auditLogService,
            new SimpleMeterRegistry()
        );
        deliveryService = new DeliveryService(
            deliveryRepository,
            pickTaskRepository,
            riderRepository,
            eventPublisher,
            outboxService,
            new FulfillmentProperties()
        );
    }

    @Test
    @DisplayName("Cold-chain and fragile flags route fragile last and propagate to OrderPacked, Delivery, and OrderDispatched")
    @SuppressWarnings("unchecked")
    void packagingFlagsPropagateAcrossPickPackAndDelivery() {
        UUID orderId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID riderId = UUID.randomUUID();

        OrderPlacedItem fragileEarlyAisle = new OrderPlacedItem(
            UUID.randomUUID(), "Glass Olive Oil", "OIL-1", 1, 500L, 500L,
            "A01", 1, "B01", "BC-1", 400, false, true, "AMBIENT");
        OrderPlacedItem chilledLateAisle = new OrderPlacedItem(
            UUID.randomUUID(), "Greek Yogurt", "YOG-1", 2, 200L, 400L,
            "A02", 2, "B02", "BC-2", 200, false, false, "CHILLED");

        OrderPlacedPayload payload = new OrderPlacedPayload(
            orderId, userId, storeId, UUID.randomUUID(),
            List.of(fragileEarlyAisle, chilledLateAisle),
            new BigDecimal("12.93"), new BigDecimal("77.62")
        );

        when(pickTaskRepository.findByOrderId(orderId)).thenReturn(Optional.empty());
        when(pickTaskRepository.save(any(PickTask.class))).thenAnswer(inv -> inv.getArgument(0));

        pickService.createPickTask(payload);

        ArgumentCaptor<PickTask> taskCaptor = ArgumentCaptor.forClass(PickTask.class);
        verify(pickTaskRepository).save(taskCaptor.capture());
        PickTask created = taskCaptor.getValue();
        created.setId(UUID.randomUUID());

        assertThat(created.isColdChainRequired()).isTrue();
        assertThat(created.isFragileRequired()).isTrue();
        assertThat(created.getItems().get(0).getProductName()).isEqualTo("Greek Yogurt");
        assertThat(created.getItems().get(1).getProductName()).isEqualTo("Glass Olive Oil");
        assertThat(created.getItems().get(1).getPickSequence()).isEqualTo(2);

        created.setStatus(PickTaskStatus.IN_PROGRESS);
        when(pickTaskRepository.findByOrderIdForUpdate(orderId)).thenReturn(Optional.of(created));
        when(pickItemRepository.countByPickTask_IdAndStatus(created.getId(), PickItemStatus.PENDING)).thenReturn(0L);
        when(pickItemRepository.countByPickTask_IdAndStatus(created.getId(), PickItemStatus.PICKED)).thenReturn(2L);
        when(pickItemRepository.countByPickTask_IdAndStatus(created.getId(), PickItemStatus.SUBSTITUTED)).thenReturn(0L);
        when(warehouseClient.getStoreCoordinates(storeId))
            .thenReturn(new StoreCoordinates(new BigDecimal("12.91"), new BigDecimal("77.64")));

        pickService.markPacked(orderId, UUID.randomUUID(), "careful");

        ArgumentCaptor<Map<String, Object>> packedPayload = ArgumentCaptor.forClass(Map.class);
        verify(outboxService).publish(eq("Fulfillment"), eq(orderId.toString()), eq("OrderPacked"), packedPayload.capture());
        assertThat(packedPayload.getValue())
            .containsEntry("coldChainRequired", true)
            .containsEntry("fragileRequired", true);

        Rider rider = new Rider();
        rider.setId(riderId);
        rider.setName("Kiran");
        rider.setStoreId(storeId);
        rider.setAvailable(true);
        when(pickTaskRepository.findByOrderId(orderId)).thenReturn(Optional.of(created));
        when(deliveryRepository.findByOrderId(orderId)).thenReturn(Optional.empty());
        when(riderRepository.findById(riderId)).thenReturn(Optional.of(rider));
        when(deliveryRepository.save(any(Delivery.class))).thenAnswer(inv -> {
            Delivery d = inv.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });

        DeliveryResponse dispatched = deliveryService.assignRider(orderId, riderId, 12);
        assertThat(dispatched.coldChainRequired()).isTrue();
        assertThat(dispatched.fragileRequired()).isTrue();

        ArgumentCaptor<Map<String, Object>> dispatchedPayload = ArgumentCaptor.forClass(Map.class);
        verify(outboxService).publish(eq("Fulfillment"), eq(orderId.toString()), eq("OrderDispatched"), dispatchedPayload.capture());
        assertThat(dispatchedPayload.getValue())
            .containsEntry("coldChainRequired", true)
            .containsEntry("fragileRequired", true);
    }

    @Test
    @DisplayName("markItem rejects invalid quantities, self-substitution, and conflicting transitions while remaining idempotent on identical retry")
    void markItemGuardsValidationAndIdempotency() {
        UUID orderId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID pickerId = UUID.randomUUID();

        PickTask task = new PickTask();
        task.setId(taskId);
        task.setOrderId(orderId);
        task.setStoreId(UUID.randomUUID());
        task.setStatus(PickTaskStatus.IN_PROGRESS);

        PickItem item = new PickItem();
        item.setId(UUID.randomUUID());
        item.setPickTask(task);
        item.setProductId(productId);
        item.setProductName("Apples");
        item.setQuantity(3);
        item.setPickedQty(0);
        item.setStatus(PickItemStatus.PENDING);

        when(pickTaskRepository.findByOrderIdForUpdate(orderId)).thenReturn(Optional.of(task));
        when(pickItemRepository.findByPickTask_OrderIdAndProductId(orderId, productId)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> pickService.markItem(
            orderId, productId, new MarkItemPickedRequest(PickItemStatus.PICKED, 0, null, null), pickerId))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> pickService.markItem(
            orderId, productId, new MarkItemPickedRequest(PickItemStatus.SUBSTITUTED, 2, productId, "self"), pickerId))
            .isInstanceOf(IllegalArgumentException.class);

        when(pickItemRepository.save(any(PickItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pickItemRepository.countByPickTask_IdAndStatus(taskId, PickItemStatus.PENDING)).thenReturn(1L);

        MarkItemPickedRequest pickedReq = new MarkItemPickedRequest(PickItemStatus.PICKED, 3, null, "ok");
        pickService.markItem(orderId, productId, pickedReq, pickerId);
        pickService.markItem(orderId, productId, pickedReq, pickerId);
        verify(pickItemRepository, times(1)).save(item);

        assertThatThrownBy(() -> pickService.markItem(
            orderId, productId, new MarkItemPickedRequest(PickItemStatus.MISSING, 0, null, "flip"), pickerId))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("onBatchCompleted and completeBatch are idempotent when called twice")
    void batchCompletionIsStrictlyIdempotent() {
        UUID batchId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();

        PickTask task = new PickTask();
        task.setId(UUID.randomUUID());
        task.setOrderId(orderId);
        task.setUserId(UUID.randomUUID());
        task.setStoreId(storeId);
        task.setStatus(PickTaskStatus.IN_PROGRESS);
        task.setStartedAt(Instant.now().minusSeconds(60));

        when(pickTaskRepository.findByOrderIdForUpdate(orderId)).thenReturn(Optional.of(task));
        when(pickTaskRepository.save(any(PickTask.class))).thenAnswer(inv -> inv.getArgument(0));
        when(warehouseClient.getStoreCoordinates(storeId))
            .thenReturn(new StoreCoordinates(new BigDecimal("12.91"), new BigDecimal("77.64")));

        pickService.onBatchCompleted(task);
        pickService.onBatchCompleted(task);
        verify(outboxService, times(1)).publish(eq("Fulfillment"), eq(orderId.toString()), eq("OrderPacked"), any());

        PickBatch completedBatch = new PickBatch();
        completedBatch.setId(batchId);
        completedBatch.setStoreId(storeId);
        completedBatch.setStatus("COMPLETED");
        completedBatch.setCompletedAt(Instant.now());

        when(pickBatchRepository.findByIdForUpdate(batchId)).thenReturn(Optional.of(completedBatch));
        when(pickBatchRepository.findById(batchId)).thenReturn(Optional.of(completedBatch));
        when(pickTaskRepository.findByBatchId(batchId)).thenReturn(List.of());

        BatchPickResponse resp = new BatchPickingService(
            pickBatchRepository, pickTaskRepository, pickItemRepository,
            new AisleRoutingOptimizer(), auditLogService, pickService
        ).completeBatch(batchId);

        assertThat(resp.status()).isEqualTo("COMPLETED");
        verify(pickBatchRepository, never()).save(any());
        verify(auditLogService, never()).log(any(), eq("BATCH_PICK_COMPLETED"), any(), any(), any());
    }
}
