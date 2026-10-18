package com.instacommerce.inventory.service;

import com.instacommerce.inventory.domain.model.StockItem;
import com.instacommerce.inventory.exception.ProductNotFoundException;
import com.instacommerce.inventory.repository.StockItemRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service managing automated daily stock reconciliation against WMS truth (Issue #572)
 * and real-time missing item cycle count freeze alerts (Issue #575).
 */
@Service
public class StockReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(StockReconciliationService.class);

    private final StockItemRepository stockItemRepository;
    private final OutboxService outboxService;

    public StockReconciliationService(StockItemRepository stockItemRepository, OutboxService outboxService) {
        this.stockItemRepository = stockItemRepository;
        this.outboxService = outboxService;
    }

    public record PhysicalStockCount(UUID productId, int physicalCount) {}

    public record DriftItem(
        UUID productId,
        UUID storeId,
        int systemStock,
        int physicalStock,
        int driftQuantity,
        boolean isFrozen
    ) {}

    public record ReconciliationSummary(
        UUID storeId,
        int totalAudited,
        int discrepanciesFound,
        List<DriftItem> discrepancies,
        Instant completedAt
    ) {}

    public record MissingItemAlert(
        UUID productId,
        UUID storeId,
        int missingQuantity,
        String pickerId,
        String aisleLocation
    ) {}

    public record CycleCountTask(
        UUID taskId,
        UUID productId,
        UUID storeId,
        String status,
        String urgency,
        Instant createdAt
    ) {}

    /**
     * Executes reconciliation comparing system stock (onHand + reserved) against physical WMS inventory (Issue #572).
     */
    @Transactional
    public ReconciliationSummary reconcileStock(UUID storeId, List<PhysicalStockCount> physicalCounts) {
        if (storeId == null) {
            throw new IllegalArgumentException("storeId cannot be null");
        }
        if (physicalCounts == null) {
            physicalCounts = List.of();
        }

        List<DriftItem> discrepancies = new ArrayList<>();
        Instant now = Instant.now();

        for (PhysicalStockCount item : physicalCounts) {
            StockItem stock = stockItemRepository.findByProductIdAndStoreId(item.productId(), storeId)
                .orElse(null);

            if (stock == null) {
                log.warn("Reconciliation found SKU {} in physical WMS but missing in system inventory for store {}",
                    item.productId(), storeId);
                continue;
            }

            int systemStock = stock.getOnHand() + stock.getReserved();
            int drift = item.physicalCount() - systemStock;

            if (drift != 0) {
                log.warn("Stock drift detected for product {} in store {}: system={}, physical={}, drift={}",
                    item.productId(), storeId, systemStock, item.physicalCount(), drift);

                discrepancies.add(new DriftItem(
                    item.productId(),
                    storeId,
                    systemStock,
                    item.physicalCount(),
                    drift,
                    stock.isFrozen()
                ));

                // Publish StockDriftDetected event to Kafka outbox
                Map<String, Object> eventPayload = new LinkedHashMap<>();
                eventPayload.put("productId", item.productId().toString());
                eventPayload.put("storeId", storeId.toString());
                eventPayload.put("systemStock", systemStock);
                eventPayload.put("physicalStock", item.physicalCount());
                eventPayload.put("driftQuantity", drift);
                eventPayload.put("onHand", stock.getOnHand());
                eventPayload.put("reserved", stock.getReserved());
                eventPayload.put("detectedAt", now.toString());

                outboxService.publish("StockItem", item.productId().toString(),
                    "StockDriftDetected", eventPayload);
            }
        }

        log.info("Stock reconciliation completed for store {}: {} audited, {} discrepancies",
            storeId, physicalCounts.size(), discrepancies.size());

        return new ReconciliationSummary(storeId, physicalCounts.size(), discrepancies.size(), discrepancies, now);
    }

    /**
     * Freezes available stock when an item is marked missing by a picker and generates an urgent
     * physical cycle count audit task on dark store handhelds (Issue #575).
     */
    @Transactional
    public CycleCountTask reportMissingItem(MissingItemAlert alert) {
        if (alert.productId() == null || alert.storeId() == null) {
            throw new IllegalArgumentException("productId and storeId cannot be null");
        }

        StockItem stock = stockItemRepository.findByProductIdAndStoreId(alert.productId(), alert.storeId())
            .orElseThrow(() -> new ProductNotFoundException(alert.productId(), alert.storeId()));

        // Freeze stock immediately so no other customers order the phantom units
        stock.setFrozen(true);
        stockItemRepository.save(stock);

        UUID taskId = UUID.randomUUID();
        Instant now = Instant.now();

        // Emit DarkStoreCycleCountTaskCreated for mobile handheld device
        Map<String, Object> taskPayload = new LinkedHashMap<>();
        taskPayload.put("taskId", taskId.toString());
        taskPayload.put("productId", alert.productId().toString());
        taskPayload.put("storeId", alert.storeId().toString());
        taskPayload.put("missingQuantity", alert.missingQuantity());
        taskPayload.put("pickerId", alert.pickerId());
        taskPayload.put("aisleLocation", alert.aisleLocation());
        taskPayload.put("urgency", "CRITICAL");
        taskPayload.put("createdAt", now.toString());

        outboxService.publish("CycleCountTask", taskId.toString(),
            "DarkStoreCycleCountTaskCreated", taskPayload);

        log.warn("SKU {} at store {} frozen due to missing item report from picker {}. Task {} dispatched.",
            alert.productId(), alert.storeId(), alert.pickerId(), taskId);

        return new CycleCountTask(taskId, alert.productId(), alert.storeId(), "PENDING_AUDIT", "CRITICAL", now);
    }

    /**
     * Completes cycle count audit, updates verified physical count, and unfreezes stock.
     */
    @Transactional
    public void unfreezeStock(UUID productId, UUID storeId, int verifiedPhysicalQty) {
        if (verifiedPhysicalQty < 0) {
            throw new IllegalArgumentException("verifiedPhysicalQty cannot be negative: " + verifiedPhysicalQty);
        }

        StockItem stock = stockItemRepository.findByProductIdAndStoreId(productId, storeId)
            .orElseThrow(() -> new ProductNotFoundException(productId, storeId));

        stock.setFrozen(false);
        stock.setOnHand(verifiedPhysicalQty);
        stockItemRepository.save(stock);

        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("productId", productId.toString());
        auditPayload.put("storeId", storeId.toString());
        auditPayload.put("verifiedPhysicalQty", verifiedPhysicalQty);
        auditPayload.put("unfrozenAt", Instant.now().toString());

        outboxService.publish("StockItem", productId.toString(), "CycleCountAuditCompleted", auditPayload);
        log.info("SKU {} at store {} unfrozen with verified physical count {}", productId, storeId, verifiedPhysicalQty);
    }
}
