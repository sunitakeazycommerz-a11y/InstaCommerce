package com.instacommerce.fulfillment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.fulfillment.client.StoreCoordinates;
import com.instacommerce.fulfillment.client.WarehouseClient;
import com.instacommerce.fulfillment.config.FulfillmentProperties;
import com.instacommerce.fulfillment.domain.model.PickItem;
import com.instacommerce.fulfillment.domain.model.PickItemStatus;
import com.instacommerce.fulfillment.domain.model.PickTask;
import com.instacommerce.fulfillment.domain.model.PickTaskStatus;
import com.instacommerce.fulfillment.dto.response.DeliveryResponse;
import com.instacommerce.fulfillment.repository.PickItemRepository;
import com.instacommerce.fulfillment.repository.PickTaskRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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

@ExtendWith(MockitoExtension.class)
class PickServiceDispatchTest {

    @Mock
    private PickTaskRepository pickTaskRepository;

    @Mock
    private PickItemRepository pickItemRepository;

    @Mock
    private SubstitutionService substitutionService;

    @Mock
    private DeliveryService deliveryService;

    @Mock
    private OutboxService outboxService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private WarehouseClient warehouseClient;

    @Captor
    private ArgumentCaptor<Map<String, Object>> payloadCaptor;

    private FulfillmentProperties fulfillmentProperties;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        fulfillmentProperties = new FulfillmentProperties();
        meterRegistry = new SimpleMeterRegistry();
        when(warehouseClient.getStoreCoordinates(anyString()))
            .thenReturn(new StoreCoordinates(new BigDecimal("12.9716"), new BigDecimal("77.5946")));
    }

    @Test
    void inlineAssignmentDisabledByDefault_publishesOutboxButSkipsRiderAssignment() {
        PickService pickService = new PickService(
            pickTaskRepository, pickItemRepository, substitutionService,
            deliveryService, outboxService, eventPublisher,
            fulfillmentProperties, meterRegistry, warehouseClient);

        PickTask task = buildCompletableTask();
        when(pickTaskRepository.findByOrderId(task.getOrderId())).thenReturn(Optional.of(task));
        when(pickItemRepository.countByPickTask_IdAndStatus(task.getId(), PickItemStatus.PENDING)).thenReturn(0L);
        when(pickTaskRepository.save(any(PickTask.class))).thenAnswer(inv -> inv.getArgument(0));

        pickService.markPacked(task.getOrderId(), UUID.randomUUID(), "test-note");

        verify(outboxService).publish(eq("Fulfillment"), eq(task.getOrderId().toString()),
            eq("OrderPacked"), payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload).containsEntry("pickupLat", new BigDecimal("12.9716"));
        assertThat(payload).containsEntry("pickupLng", new BigDecimal("77.5946"));
        verify(deliveryService, never()).assignRider(any(PickTask.class));
        assertThat(skippedCount()).isOne();
        assertThat(invokedCount()).isZero();
    }

    @Test
    void inlineAssignmentEnabled_publishesOutboxAndInvokesRiderAssignment() {
        fulfillmentProperties.getDispatch().setInlineAssignmentEnabled(true);
        PickService pickService = new PickService(
            pickTaskRepository, pickItemRepository, substitutionService,
            deliveryService, outboxService, eventPublisher,
            fulfillmentProperties, meterRegistry, warehouseClient);

        PickTask task = buildCompletableTask();
        when(pickTaskRepository.findByOrderId(task.getOrderId())).thenReturn(Optional.of(task));
        when(pickItemRepository.countByPickTask_IdAndStatus(task.getId(), PickItemStatus.PENDING)).thenReturn(0L);
        when(pickTaskRepository.save(any(PickTask.class))).thenAnswer(inv -> inv.getArgument(0));
        when(deliveryService.assignRider(any(PickTask.class))).thenReturn(Optional.of(
            new DeliveryResponse(UUID.randomUUID(), task.getOrderId(), "ASSIGNED",
                UUID.randomUUID(), "Test Rider", 15, Instant.now(), null)));

        pickService.markPacked(task.getOrderId(), UUID.randomUUID(), "test-note");

        verify(outboxService).publish(eq("Fulfillment"), eq(task.getOrderId().toString()),
            eq("OrderPacked"), payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload).containsEntry("pickupLat", new BigDecimal("12.9716"));
        assertThat(payload).containsEntry("pickupLng", new BigDecimal("77.5946"));
        verify(deliveryService).assignRider(any(PickTask.class));
        assertThat(invokedCount()).isOne();
        assertThat(skippedCount()).isZero();
    }

    private PickTask buildCompletableTask() {
        PickTask task = new PickTask();
        task.setId(UUID.randomUUID());
        task.setOrderId(UUID.randomUUID());
        task.setUserId(UUID.randomUUID());
        task.setStoreId("store-1");
        task.setStatus(PickTaskStatus.IN_PROGRESS);
        task.setStartedAt(Instant.now());
        task.setItems(List.of());
        return task;
    }

    private double invokedCount() {
        return meterRegistry.counter("fulfillment.dispatch.inline_assignment.invoked").count();
    }

    private double skippedCount() {
        return meterRegistry.counter("fulfillment.dispatch.inline_assignment.skipped").count();
    }
}
