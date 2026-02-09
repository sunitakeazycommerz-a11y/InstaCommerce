package com.instacommerce.fulfillment.service;

import com.instacommerce.fulfillment.consumer.OrderPlacedItem;
import com.instacommerce.fulfillment.consumer.OrderPlacedPayload;
import com.instacommerce.fulfillment.domain.model.PickItem;
import com.instacommerce.fulfillment.domain.model.PickItemStatus;
import com.instacommerce.fulfillment.domain.model.PickTask;
import com.instacommerce.fulfillment.domain.model.PickTaskStatus;
import com.instacommerce.fulfillment.dto.mapper.FulfillmentMapper;
import com.instacommerce.fulfillment.dto.request.MarkItemPickedRequest;
import com.instacommerce.fulfillment.dto.response.PickItemResponse;
import com.instacommerce.fulfillment.dto.response.PickTaskResponse;
import com.instacommerce.fulfillment.event.OrderStatusUpdateEvent;
import com.instacommerce.fulfillment.exception.InvalidPickTaskStateException;
import com.instacommerce.fulfillment.exception.PickItemNotFoundException;
import com.instacommerce.fulfillment.exception.PickTaskNotFoundException;
import com.instacommerce.fulfillment.repository.PickItemRepository;
import com.instacommerce.fulfillment.repository.PickTaskRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PickService {
    private static final Logger logger = LoggerFactory.getLogger(PickService.class);

    private final PickTaskRepository pickTaskRepository;
    private final PickItemRepository pickItemRepository;
    private final SubstitutionService substitutionService;
    private final DeliveryService deliveryService;
    private final OutboxService outboxService;
    private final ApplicationEventPublisher eventPublisher;

    public PickService(PickTaskRepository pickTaskRepository,
                       PickItemRepository pickItemRepository,
                       SubstitutionService substitutionService,
                       DeliveryService deliveryService,
                       OutboxService outboxService,
                       ApplicationEventPublisher eventPublisher) {
        this.pickTaskRepository = pickTaskRepository;
        this.pickItemRepository = pickItemRepository;
        this.substitutionService = substitutionService;
        this.deliveryService = deliveryService;
        this.outboxService = outboxService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void createPickTask(OrderPlacedPayload payload) {
        Optional<PickTask> existing = pickTaskRepository.findByOrderId(payload.orderId());
        if (existing.isPresent()) {
            return;
        }
        PickTask task = new PickTask();
        task.setOrderId(payload.orderId());
        task.setUserId(payload.userId());
        task.setStoreId(payload.storeId());
        task.setPaymentId(payload.paymentId());
        task.setStatus(PickTaskStatus.PENDING);

        List<OrderPlacedItem> itemsPayload = payload.items() == null ? List.of() : payload.items();
        List<PickItem> items = itemsPayload.stream().map(item -> toPickItem(task, item)).toList();
        task.setItems(items);
        try {
            pickTaskRepository.save(task);
        } catch (DataIntegrityViolationException ex) {
            logger.warn("Pick task already exists for order {}", payload.orderId());
        }
    }

    @Transactional(readOnly = true)
    public List<PickTaskResponse> listPendingTasks(String storeId) {
        return pickTaskRepository.findByStoreIdAndStatusIn(storeId,
                List.of(PickTaskStatus.PENDING, PickTaskStatus.IN_PROGRESS))
            .stream()
            .map(FulfillmentMapper::toPickTaskResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<PickTaskResponse> listPendingTasks(String storeId,
            org.springframework.data.domain.Pageable pageable) {
        return pickTaskRepository.findByStoreIdAndStatusIn(storeId,
                List.of(PickTaskStatus.PENDING, PickTaskStatus.IN_PROGRESS), pageable)
            .map(FulfillmentMapper::toPickTaskResponse);
    }

    @Transactional(readOnly = true)
    public List<PickItemResponse> listItems(UUID orderId) {
        PickTask task = pickTaskRepository.findByOrderId(orderId)
            .orElseThrow(() -> new PickTaskNotFoundException(orderId));
        return pickItemRepository.findByPickTask_Id(task.getId()).stream()
            .map(FulfillmentMapper::toPickItemResponse)
            .toList();
    }

    @Transactional
    public PickItemResponse markItem(UUID orderId, UUID productId, MarkItemPickedRequest request, UUID pickerId) {
        PickTask task = pickTaskRepository.findByOrderId(orderId)
            .orElseThrow(() -> new PickTaskNotFoundException(orderId));
        if (task.getStatus() == PickTaskStatus.COMPLETED || task.getStatus() == PickTaskStatus.CANCELLED) {
            throw new InvalidPickTaskStateException(orderId, task.getStatus(), "update items");
        }
        PickItem item = pickItemRepository.findByPickTask_OrderIdAndProductId(orderId, productId)
            .orElseThrow(() -> new PickItemNotFoundException(orderId, productId));
        PickItemStatus previousStatus = item.getStatus();
        applyItemUpdate(item, request);
        pickItemRepository.save(item);

        if (task.getStatus() == PickTaskStatus.PENDING) {
            task.setStatus(PickTaskStatus.IN_PROGRESS);
            task.setStartedAt(Instant.now());
            if (pickerId != null) {
                task.setPickerId(pickerId);
            }
            pickTaskRepository.save(task);
            eventPublisher.publishEvent(new OrderStatusUpdateEvent(orderId, "PACKING", "picking-started"));
        }

        if (request.status() == PickItemStatus.MISSING && previousStatus != PickItemStatus.MISSING) {
            int missingQty = item.getQuantity() - item.getPickedQty();
            substitutionService.handleMissingItem(task, item, missingQty);
        }

        if (isTaskCompleted(task)) {
            completeTask(task);
        }
        return FulfillmentMapper.toPickItemResponse(item);
    }

    @Transactional
    public PickTaskResponse markPacked(UUID orderId, UUID pickerId, String note) {
        PickTask task = pickTaskRepository.findByOrderId(orderId)
            .orElseThrow(() -> new PickTaskNotFoundException(orderId));
        if (task.getStatus() == PickTaskStatus.COMPLETED) {
            return FulfillmentMapper.toPickTaskResponse(task);
        }
        if (task.getStatus() == PickTaskStatus.CANCELLED) {
            throw new InvalidPickTaskStateException(orderId, task.getStatus(), "mark packed");
        }
        if (!areItemsPicked(task)) {
            throw new InvalidPickTaskStateException(orderId, task.getStatus(), "mark packed");
        }
        boolean wasPending = task.getStatus() == PickTaskStatus.PENDING;
        if (wasPending) {
            task.setStatus(PickTaskStatus.IN_PROGRESS);
            task.setStartedAt(Instant.now());
        }
        if (pickerId != null) {
            task.setPickerId(pickerId);
        }
        task.setCompletedAt(Instant.now());
        task.setStatus(PickTaskStatus.COMPLETED);
        PickTask saved = pickTaskRepository.save(task);
        if (wasPending) {
            eventPublisher.publishEvent(new OrderStatusUpdateEvent(orderId, "PACKING", "picking-started"));
        }
        publishPacked(saved, note);
        return FulfillmentMapper.toPickTaskResponse(saved);
    }

    private void applyItemUpdate(PickItem item, MarkItemPickedRequest request) {
        int pickedQty = safeQty(request, item.getQuantity());
        if (pickedQty > item.getQuantity()) {
            throw new IllegalArgumentException("Picked quantity cannot exceed ordered quantity");
        }
        if (request.status() == PickItemStatus.SUBSTITUTED && request.substituteProductId() == null) {
            throw new IllegalArgumentException("Substitution requires substituteProductId");
        }
        item.setPickedQty(pickedQty);
        item.setStatus(request.status());
        item.setNote(request.note());
        item.setSubstituteProductId(request.substituteProductId());
    }

    private int safeQty(MarkItemPickedRequest request, int defaultQty) {
        if (request.pickedQty() != null) {
            return request.pickedQty();
        }
        if (request.status() == PickItemStatus.PICKED || request.status() == PickItemStatus.SUBSTITUTED) {
            return defaultQty;
        }
        return 0;
    }

    private boolean isTaskCompleted(PickTask task) {
        if (task.getStatus() == PickTaskStatus.COMPLETED) {
            return false;
        }
        return areItemsPicked(task);
    }

    private boolean areItemsPicked(PickTask task) {
        return pickItemRepository.countByPickTask_IdAndStatus(task.getId(), PickItemStatus.PENDING) == 0;
    }

    private void completeTask(PickTask task) {
        task.setStatus(PickTaskStatus.COMPLETED);
        task.setCompletedAt(Instant.now());
        PickTask saved = pickTaskRepository.save(task);
        publishPacked(saved, "auto-complete");
    }

    private void publishPacked(PickTask task, String note) {
        eventPublisher.publishEvent(new OrderStatusUpdateEvent(task.getOrderId(), "PACKED", "order-packed"));
        outboxService.publish("Fulfillment", task.getOrderId().toString(), "OrderPacked",
            java.util.Map.of(
                "orderId", task.getOrderId(),
                "userId", task.getUserId(),
                "storeId", task.getStoreId(),
                "packedAt", task.getCompletedAt(),
                "note", note == null ? "" : note
            ));
        deliveryService.assignRider(task)
            .ifPresentOrElse(
                delivery -> logger.info("Assigned rider {} for order {}", delivery.riderId(), task.getOrderId()),
                () -> logger.warn("Order {} packed without rider assignment", task.getOrderId()));
    }

    private PickItem toPickItem(PickTask task, OrderPlacedItem item) {
        PickItem pickItem = new PickItem();
        pickItem.setPickTask(task);
        pickItem.setProductId(item.productId());
        pickItem.setProductName(item.productName());
        pickItem.setSku(item.sku());
        pickItem.setQuantity(item.quantity());
        pickItem.setPickedQty(0);
        pickItem.setUnitPriceCents(item.unitPriceCents());
        pickItem.setLineTotalCents(item.lineTotalCents());
        pickItem.setStatus(PickItemStatus.PENDING);
        return pickItem;
    }
}
