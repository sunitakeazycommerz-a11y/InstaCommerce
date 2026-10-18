package com.instacommerce.fulfillment.service;

import com.instacommerce.fulfillment.domain.model.PickBatch;
import com.instacommerce.fulfillment.domain.model.PickItem;
import com.instacommerce.fulfillment.domain.model.PickTask;
import com.instacommerce.fulfillment.domain.model.PickTaskStatus;
import com.instacommerce.fulfillment.dto.request.CreateBatchRequest;
import com.instacommerce.fulfillment.dto.response.BatchPickResponse;
import com.instacommerce.fulfillment.dto.response.BatchPickRouteStep;
import com.instacommerce.fulfillment.dto.response.BatchToteAssignment;
import com.instacommerce.fulfillment.exception.InvalidCompartmentScanException;
import com.instacommerce.fulfillment.exception.PickTaskNotFoundException;
import com.instacommerce.fulfillment.repository.PickBatchRepository;
import com.instacommerce.fulfillment.repository.PickItemRepository;
import com.instacommerce.fulfillment.repository.PickTaskRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Multi-picker order batching service for high-velocity dark store aisles.
 * Combines up to 4 small orders into a single multi-compartment cart run with color-coded
 * tote validation and consolidated S-curve serpentine aisle routing.
 */
@Service
public class BatchPickingService {
    private static final Logger logger = LoggerFactory.getLogger(BatchPickingService.class);

    private static final String[] TOTE_COLORS = {"RED", "BLUE", "GREEN", "YELLOW"};
    private static final String[] TOTE_PREFIXES = {"TOTE-RED-", "TOTE-BLUE-", "TOTE-GREEN-", "TOTE-YELLOW-"};

    private final PickBatchRepository pickBatchRepository;
    private final PickTaskRepository pickTaskRepository;
    private final PickItemRepository pickItemRepository;
    private final AisleRoutingOptimizer aisleRoutingOptimizer;
    private final AuditLogService auditLogService;

    public BatchPickingService(PickBatchRepository pickBatchRepository,
                               PickTaskRepository pickTaskRepository,
                               PickItemRepository pickItemRepository,
                               AisleRoutingOptimizer aisleRoutingOptimizer,
                               AuditLogService auditLogService) {
        this.pickBatchRepository = pickBatchRepository;
        this.pickTaskRepository = pickTaskRepository;
        this.pickItemRepository = pickItemRepository;
        this.aisleRoutingOptimizer = aisleRoutingOptimizer;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public BatchPickResponse createBatch(CreateBatchRequest request, UUID pickerId) {
        int maxOrders = (request.maxOrders() != null && request.maxOrders() > 0 && request.maxOrders() <= 4)
            ? request.maxOrders() : 4;

        List<PickTask> pendingTasks = pickTaskRepository
            .findByStoreIdAndStatusAndBatchIdIsNullOrderByCreatedAtAsc(
                request.storeId(), PickTaskStatus.PENDING, PageRequest.of(0, maxOrders));

        if (pendingTasks.isEmpty()) {
            throw new IllegalStateException("No pending unbatched pick tasks available for store " + request.storeId());
        }

        PickBatch batch = new PickBatch();
        batch.setStoreId(request.storeId());
        batch.setPickerId(pickerId);
        batch.setCartId(request.cartId() != null && !request.cartId().isBlank() ? request.cartId() : "CART-01");
        batch.setStatus("IN_PROGRESS");
        batch.setStartedAt(Instant.now());
        batch.setTotalOrders(pendingTasks.size());

        List<PickItem> allItems = new ArrayList<>();
        List<BatchToteAssignment> totes = new ArrayList<>();
        Map<UUID, PickTask> taskByOrderId = new HashMap<>();

        for (int i = 0; i < pendingTasks.size(); i++) {
            PickTask task = pendingTasks.get(i);
            String color = TOTE_COLORS[i % TOTE_COLORS.length];
            String barcode = TOTE_PREFIXES[i % TOTE_PREFIXES.length] +
                task.getOrderId().toString().substring(0, 8).toUpperCase();

            task.setBatchId(batch.getId());
            task.setToteColor(color);
            task.setToteBarcode(barcode);
            task.setStatus(PickTaskStatus.IN_PROGRESS);
            task.setStartedAt(Instant.now());
            if (pickerId != null) {
                task.setPickerId(pickerId);
            }
            taskByOrderId.put(task.getOrderId(), task);
            totes.add(new BatchToteAssignment(task.getOrderId(), barcode, color));

            List<PickItem> taskItems = pickItemRepository.findByPickTask_Id(task.getId());
            allItems.addAll(taskItems);
        }

        batch.setTotalItems(allItems.size());
        PickBatch savedBatch = pickBatchRepository.save(batch);

        for (PickTask task : pendingTasks) {
            task.setBatchId(savedBatch.getId());
            pickTaskRepository.save(task);
        }

        List<PickItem> optimizedRouteItems = aisleRoutingOptimizer.optimizePickPath(allItems);
        List<BatchPickRouteStep> routeSteps = buildRouteSteps(optimizedRouteItems, taskByOrderId);

        auditLogService.log(pickerId, "BATCH_PICK_STARTED", "PickBatch", savedBatch.getId().toString(),
            Map.of("totalOrders", savedBatch.getTotalOrders(), "totalItems", savedBatch.getTotalItems()));

        return new BatchPickResponse(
            savedBatch.getId(),
            savedBatch.getStoreId(),
            savedBatch.getPickerId(),
            savedBatch.getStatus(),
            savedBatch.getCartId(),
            savedBatch.getTotalOrders(),
            savedBatch.getTotalItems(),
            savedBatch.getStartedAt(),
            savedBatch.getCompletedAt(),
            totes,
            routeSteps
        );
    }

    @Transactional(readOnly = true)
    public BatchPickResponse getBatch(UUID batchId) {
        PickBatch batch = pickBatchRepository.findById(batchId)
            .orElseThrow(() -> new IllegalArgumentException("Batch not found: " + batchId));

        List<PickTask> tasks = pickTaskRepository.findByBatchId(batchId);
        List<BatchToteAssignment> totes = new ArrayList<>();
        List<PickItem> allItems = new ArrayList<>();
        Map<UUID, PickTask> taskByOrderId = new HashMap<>();

        for (PickTask task : tasks) {
            totes.add(new BatchToteAssignment(task.getOrderId(), task.getToteBarcode(), task.getToteColor()));
            taskByOrderId.put(task.getOrderId(), task);
            allItems.addAll(pickItemRepository.findByPickTask_Id(task.getId()));
        }

        List<PickItem> optimizedRouteItems = aisleRoutingOptimizer.optimizePickPath(allItems);
        List<BatchPickRouteStep> routeSteps = buildRouteSteps(optimizedRouteItems, taskByOrderId);

        return new BatchPickResponse(
            batch.getId(),
            batch.getStoreId(),
            batch.getPickerId(),
            batch.getStatus(),
            batch.getCartId(),
            batch.getTotalOrders(),
            batch.getTotalItems(),
            batch.getStartedAt(),
            batch.getCompletedAt(),
            totes,
            routeSteps
        );
    }

    @Transactional
    public void validateCompartment(UUID batchId, UUID orderId, String scannedBarcode) {
        PickTask task = pickTaskRepository.findByOrderId(orderId)
            .orElseThrow(() -> new PickTaskNotFoundException(orderId));

        if (task.getBatchId() == null || !task.getBatchId().equals(batchId)) {
            throw new InvalidCompartmentScanException("Order " + orderId + " is not assigned to batch " + batchId);
        }

        String expectedBarcode = task.getToteBarcode();
        if (expectedBarcode == null || !expectedBarcode.equalsIgnoreCase(scannedBarcode.trim())) {
            throw new InvalidCompartmentScanException("Scanned tote barcode '" + scannedBarcode +
                "' does not match expected compartment '" + expectedBarcode + "' (" + task.getToteColor() + ")");
        }

        logger.info("Validated tote compartment {} for order {} in batch {}",
            scannedBarcode, orderId, batchId);
    }

    @Transactional
    public BatchPickResponse completeBatch(UUID batchId) {
        PickBatch batch = pickBatchRepository.findById(batchId)
            .orElseThrow(() -> new IllegalArgumentException("Batch not found: " + batchId));

        batch.setStatus("COMPLETED");
        batch.setCompletedAt(Instant.now());
        PickBatch saved = pickBatchRepository.save(batch);

        List<PickTask> tasks = pickTaskRepository.findByBatchId(batchId);
        for (PickTask task : tasks) {
            if (task.getStatus() != PickTaskStatus.COMPLETED && task.getStatus() != PickTaskStatus.CANCELLED) {
                task.setStatus(PickTaskStatus.COMPLETED);
                task.setCompletedAt(Instant.now());
                pickTaskRepository.save(task);
            }
        }

        auditLogService.log(batch.getPickerId(), "BATCH_PICK_COMPLETED", "PickBatch", batch.getId().toString(),
            Map.of("totalOrders", batch.getTotalOrders(), "completedAt", batch.getCompletedAt()));

        return getBatch(batchId);
    }

    private List<BatchPickRouteStep> buildRouteSteps(List<PickItem> items, Map<UUID, PickTask> taskByOrderId) {
        List<BatchPickRouteStep> steps = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            PickItem item = items.get(i);
            UUID orderId = item.getPickTask().getOrderId();
            PickTask task = taskByOrderId.get(orderId);
            steps.add(new BatchPickRouteStep(
                i + 1,
                item.getId(),
                orderId,
                item.getProductName(),
                item.getSku(),
                item.getQuantity(),
                item.getAisle(),
                item.getShelf(),
                item.getBin(),
                task != null ? task.getToteBarcode() : null,
                task != null ? task.getToteColor() : null
            ));
        }
        return steps;
    }
}
