package com.instacommerce.fulfillment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.fulfillment.client.StoreCoordinates;
import com.instacommerce.fulfillment.client.WarehouseClient;
import com.instacommerce.fulfillment.consumer.OrderPlacedItem;
import com.instacommerce.fulfillment.consumer.OrderPlacedPayload;
import com.instacommerce.fulfillment.domain.model.PickItem;
import com.instacommerce.fulfillment.domain.model.PickItemStatus;
import com.instacommerce.fulfillment.domain.model.PickTask;
import com.instacommerce.fulfillment.domain.model.PickTaskStatus;
import com.instacommerce.fulfillment.dto.request.MarkItemPickedRequest;
import com.instacommerce.fulfillment.dto.response.PickTaskResponse;
import com.instacommerce.fulfillment.exception.InvalidPickTaskStateException;
import com.instacommerce.fulfillment.repository.PickItemRepository;
import com.instacommerce.fulfillment.repository.PickTaskRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class PickServiceTest {

    @Mock
    private PickTaskRepository pickTaskRepository;

    @Mock
    private PickItemRepository pickItemRepository;

    @Mock
    private SubstitutionService substitutionService;

    @Mock
    private OutboxService outboxService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private WarehouseClient warehouseClient;

    @Mock
    private AisleRoutingOptimizer aisleRoutingOptimizer;

    @Mock
    private AuditLogService auditLogService;

    private PickService pickService;

    @BeforeEach
    void setUp() {
        pickService = new PickService(
            pickTaskRepository,
            pickItemRepository,
            substitutionService,
            outboxService,
            eventPublisher,
            warehouseClient,
            aisleRoutingOptimizer,
            auditLogService,
            new SimpleMeterRegistry()
        );
    }

    @Test
    void markItemAllMissingDoesNotAutoCompleteAndMarkPackedRejects() {
        UUID orderId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID pickerId = UUID.randomUUID();
        PickTask task = newPickTask(taskId, orderId, PickTaskStatus.PENDING);
        PickItem item = newPickItem(task, productId, 2);

        when(pickTaskRepository.findByOrderIdForUpdate(orderId)).thenReturn(Optional.of(task));
        when(pickItemRepository.findByPickTask_OrderIdAndProductId(orderId, productId)).thenReturn(Optional.of(item));
        when(pickTaskRepository.save(any(PickTask.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pickItemRepository.save(any(PickItem.class))).thenAnswer(inv -> inv.getArgument(0));
        stubItemCounts(taskId, 0L, 0L, 0L);

        MarkItemPickedRequest missingRequest = new MarkItemPickedRequest(PickItemStatus.MISSING, 0, null, "out of stock");
        pickService.markItem(orderId, productId, missingRequest, pickerId);

        assertThat(task.getStatus()).isEqualTo(PickTaskStatus.IN_PROGRESS);
        assertThat(task.getCompletedAt()).isNull();
        verify(outboxService, never()).publish(eq("Fulfillment"), eq(orderId.toString()), eq("OrderPacked"), any());

        assertThatThrownBy(() -> pickService.markPacked(orderId, pickerId, "trying to pack all-missing order"))
            .isInstanceOf(InvalidPickTaskStateException.class);
        verify(outboxService, never()).publish(eq("Fulfillment"), eq(orderId.toString()), eq("OrderPacked"), any());
    }

    @Test
    void markPackedRejectsWhenTaskHasNoPickItems() {
        UUID orderId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID pickerId = UUID.randomUUID();
        PickTask task = newPickTask(taskId, orderId, PickTaskStatus.PENDING);

        when(pickTaskRepository.findByOrderIdForUpdate(orderId)).thenReturn(Optional.of(task));
        stubItemCounts(taskId, 0L, 0L, 0L);

        assertThatThrownBy(() -> pickService.markPacked(orderId, pickerId, "empty task"))
            .isInstanceOf(InvalidPickTaskStateException.class);
        assertThat(task.getStatus()).isEqualTo(PickTaskStatus.PENDING);
        verify(outboxService, never()).publish(eq("Fulfillment"), eq(orderId.toString()), eq("OrderPacked"), any());
    }

    @Test
    void markItemWithRemainingPendingItemsDoesNotAutoComplete() {
        UUID orderId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID pickerId = UUID.randomUUID();
        PickTask task = newPickTask(taskId, orderId, PickTaskStatus.PENDING);
        PickItem item = newPickItem(task, productId, 1);

        when(pickTaskRepository.findByOrderIdForUpdate(orderId)).thenReturn(Optional.of(task));
        when(pickItemRepository.findByPickTask_OrderIdAndProductId(orderId, productId)).thenReturn(Optional.of(item));
        when(pickTaskRepository.save(any(PickTask.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pickItemRepository.save(any(PickItem.class))).thenAnswer(inv -> inv.getArgument(0));
        stubItemCounts(taskId, 1L, 1L, 0L);

        pickService.markItem(orderId, productId, new MarkItemPickedRequest(PickItemStatus.PICKED, 1, null, null), pickerId);

        assertThat(task.getStatus()).isEqualTo(PickTaskStatus.IN_PROGRESS);
        assertThat(task.getCompletedAt()).isNull();
        verify(outboxService, never()).publish(eq("Fulfillment"), eq(orderId.toString()), eq("OrderPacked"), any());
    }

    @Test
    void markItemAutoCompletesWhenPickedAndMissingLeaveNoPendingItems() {
        UUID orderId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID pickerId = UUID.randomUUID();
        PickTask task = newPickTask(taskId, orderId, PickTaskStatus.IN_PROGRESS);
        PickItem item = newPickItem(task, productId, 1);

        when(pickTaskRepository.findByOrderIdForUpdate(orderId)).thenReturn(Optional.of(task));
        when(pickItemRepository.findByPickTask_OrderIdAndProductId(orderId, productId)).thenReturn(Optional.of(item));
        when(pickTaskRepository.save(any(PickTask.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pickItemRepository.save(any(PickItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(warehouseClient.getStoreCoordinates(task.getStoreId()))
            .thenReturn(new StoreCoordinates(new BigDecimal("12.9716"), new BigDecimal("77.5946")));
        stubItemCounts(taskId, 0L, 1L, 0L);

        pickService.markItem(orderId, productId, new MarkItemPickedRequest(PickItemStatus.PICKED, 1, null, null), pickerId);

        assertThat(task.getStatus()).isEqualTo(PickTaskStatus.COMPLETED);
        assertThat(task.getCompletedAt()).isNotNull();
        verify(outboxService).publish(eq("Fulfillment"), eq(orderId.toString()), eq("OrderPacked"), any());
    }

    @Test
    void markPackedSucceedsWhenSubstitutedAndMissingLeaveNoPendingItems() {
        UUID orderId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID pickerId = UUID.randomUUID();
        PickTask task = newPickTask(taskId, orderId, PickTaskStatus.IN_PROGRESS);

        when(pickTaskRepository.findByOrderIdForUpdate(orderId)).thenReturn(Optional.of(task));
        when(pickTaskRepository.save(any(PickTask.class))).thenAnswer(inv -> inv.getArgument(0));
        when(warehouseClient.getStoreCoordinates(task.getStoreId())).thenReturn(null);
        stubItemCounts(taskId, 0L, 0L, 1L);

        PickTaskResponse response = pickService.markPacked(orderId, pickerId, "packed substituted order");

        assertThat(response.status()).isEqualTo(PickTaskStatus.COMPLETED.name());
        assertThat(task.getStatus()).isEqualTo(PickTaskStatus.COMPLETED);
        verify(outboxService).publish(eq("Fulfillment"), eq(orderId.toString()), eq("OrderPacked"), any());
    }

    private void stubItemCounts(UUID taskId, long pending, long picked, long substituted) {
        lenient().when(pickItemRepository.countByPickTask_IdAndStatus(taskId, PickItemStatus.PENDING)).thenReturn(pending);
        lenient().when(pickItemRepository.countByPickTask_IdAndStatus(taskId, PickItemStatus.PICKED)).thenReturn(picked);
        lenient().when(pickItemRepository.countByPickTask_IdAndStatus(taskId, PickItemStatus.SUBSTITUTED)).thenReturn(substituted);
    }

    private PickTask newPickTask(UUID taskId, UUID orderId, PickTaskStatus status) {
        PickTask task = new PickTask();
        task.setId(taskId);
        task.setOrderId(orderId);
        task.setUserId(UUID.randomUUID());
        task.setStoreId(UUID.randomUUID());
        task.setStatus(status);
        return task;
    }

    private PickItem newPickItem(PickTask task, UUID productId, int quantity) {
        PickItem item = new PickItem();
        item.setId(UUID.randomUUID());
        item.setPickTask(task);
        item.setProductId(productId);
        item.setProductName("Milk 500ml");
        item.setQuantity(quantity);
        item.setPickedQty(0);
        item.setUnitPriceCents(5000L);
        item.setLineTotalCents(5000L * quantity);
        item.setStatus(PickItemStatus.PENDING);
        return item;
    }

    @Test
    @SuppressWarnings("unchecked")
    void createPickTaskPersistsDropoffCoordinatesAndPublishesInOrderPacked() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        BigDecimal dropoffLat = new BigDecimal("12.9352");
        BigDecimal dropoffLng = new BigDecimal("77.6245");

        OrderPlacedPayload orderPlaced = new OrderPlacedPayload(
            orderId,
            userId,
            storeId,
            paymentId,
            List.of(new OrderPlacedItem(UUID.randomUUID(), "Milk", "MILK-1", 1, 5000L, 5000L)),
            dropoffLat,
            dropoffLng
        );
        when(pickTaskRepository.findByOrderId(orderId)).thenReturn(Optional.empty());
        when(pickTaskRepository.save(any(PickTask.class))).thenAnswer(inv -> inv.getArgument(0));

        pickService.createPickTask(orderPlaced);

        ArgumentCaptor<PickTask> taskCaptor = ArgumentCaptor.forClass(PickTask.class);
        verify(pickTaskRepository).save(taskCaptor.capture());
        PickTask savedTask = taskCaptor.getValue();
        savedTask.setId(UUID.randomUUID());
        savedTask.setStatus(PickTaskStatus.IN_PROGRESS);
        assertThat(savedTask.getDropoffLat()).isEqualByComparingTo(dropoffLat);
        assertThat(savedTask.getDropoffLng()).isEqualByComparingTo(dropoffLng);

        when(pickTaskRepository.findByOrderIdForUpdate(orderId)).thenReturn(Optional.of(savedTask));
        when(warehouseClient.getStoreCoordinates(storeId))
            .thenReturn(new StoreCoordinates(new BigDecimal("12.9716"), new BigDecimal("77.5946")));
        stubItemCounts(savedTask.getId(), 0L, 1L, 0L);

        pickService.markPacked(orderId, UUID.randomUUID(), "packed");

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(outboxService).publish(eq("Fulfillment"), eq(orderId.toString()), eq("OrderPacked"), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
            .containsEntry("pickupLat", new BigDecimal("12.9716"))
            .containsEntry("pickupLng", new BigDecimal("77.5946"))
            .containsEntry("dropoffLat", dropoffLat)
            .containsEntry("dropoffLng", dropoffLng);
    }
}
