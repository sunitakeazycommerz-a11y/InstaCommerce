package com.instacommerce.inventory.service;

import com.instacommerce.inventory.config.InventoryProperties;
import com.instacommerce.inventory.domain.model.StockAdjustmentLog;
import com.instacommerce.inventory.domain.model.StockItem;
import com.instacommerce.inventory.dto.mapper.InventoryMapper;
import com.instacommerce.inventory.dto.request.InventoryItemRequest;
import com.instacommerce.inventory.dto.request.StockAdjustBatchRequest;
import com.instacommerce.inventory.dto.request.StockAdjustItemRequest;
import com.instacommerce.inventory.dto.request.StockAdjustRequest;
import com.instacommerce.inventory.dto.request.StockCheckRequest;
import com.instacommerce.inventory.dto.response.StockCheckItemResponse;
import com.instacommerce.inventory.dto.response.StockCheckResponse;
import com.instacommerce.inventory.exception.InvalidStockAdjustmentException;
import com.instacommerce.inventory.exception.ProductNotFoundException;
import com.instacommerce.inventory.repository.StockAdjustmentLogRepository;
import com.instacommerce.inventory.repository.StockItemRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryService {
    private static final String LOCK_TIMEOUT_HINT = "jakarta.persistence.lock.timeout";
    private final StockItemRepository stockItemRepository;
    private final StockAdjustmentLogRepository stockAdjustmentLogRepository;
    private final EntityManager entityManager;
    private final AuditLogService auditLogService;
    private final OutboxService outboxService;
    private final InventoryProperties inventoryProperties;

    public InventoryService(StockItemRepository stockItemRepository,
                            StockAdjustmentLogRepository stockAdjustmentLogRepository,
                            EntityManager entityManager,
                            AuditLogService auditLogService,
                            OutboxService outboxService,
                            InventoryProperties inventoryProperties) {
        this.stockItemRepository = stockItemRepository;
        this.stockAdjustmentLogRepository = stockAdjustmentLogRepository;
        this.entityManager = entityManager;
        this.auditLogService = auditLogService;
        this.outboxService = outboxService;
        this.inventoryProperties = inventoryProperties;
    }

    @Transactional(readOnly = true)
    public StockCheckResponse checkAvailability(StockCheckRequest request) {
        List<UUID> productIds = request.items().stream()
            .map(InventoryItemRequest::productId)
            .toList();
        Map<UUID, StockItem> stockByProduct = stockItemRepository
            .findByStoreIdAndProductIdIn(request.storeId(), productIds).stream()
            .collect(Collectors.toMap(StockItem::getProductId, Function.identity()));
        List<StockCheckItemResponse> items = request.items().stream()
            .map(item -> {
                StockItem stock = stockByProduct.get(item.productId());
                if (stock == null) {
                    throw new ProductNotFoundException(item.productId(), request.storeId());
                }
                return InventoryMapper.toStockCheckItemResponse(stock, item.quantity());
            })
            .toList();
        return new StockCheckResponse(items);
    }

    @Transactional
    public StockCheckItemResponse adjustStock(StockAdjustRequest request) {
        StockItem stock = lockStockItem(request.productId(), request.storeId());
        UUID actorId = resolveActorId();
        int updatedOnHand = stock.getOnHand() + request.delta();
        if (updatedOnHand < 0) {
            throw new InvalidStockAdjustmentException("Resulting stock cannot be negative");
        }
        if (stock.getReserved() > updatedOnHand) {
            throw new InvalidStockAdjustmentException("Resulting stock cannot be less than reserved quantity");
        }
        stock.setOnHand(updatedOnHand);
        stockItemRepository.save(stock);
        StockAdjustmentLog log = new StockAdjustmentLog();
        log.setProductId(request.productId());
        log.setStoreId(request.storeId());
        log.setDelta(request.delta());
        log.setReason(request.reason());
        log.setReferenceId(request.referenceId());
        log.setActorId(actorId);
        stockAdjustmentLogRepository.save(log);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("storeId", request.storeId());
        details.put("delta", request.delta());
        details.put("reason", request.reason());
        if (request.referenceId() != null) {
            details.put("referenceId", request.referenceId());
        }
        auditLogService.log(actorId,
            "STOCK_ADJUSTED",
            "StockItem",
            request.productId().toString(),
            details);

        Map<String, Object> eventPayload = new LinkedHashMap<>();
        eventPayload.put("productId", request.productId().toString());
        eventPayload.put("storeId", request.storeId());
        eventPayload.put("delta", request.delta());
        eventPayload.put("reason", request.reason());
        eventPayload.put("newOnHand", updatedOnHand);
        eventPayload.put("adjustedAt", Instant.now().toString());
        outboxService.publish("StockItem", request.productId().toString(),
            "StockAdjusted", eventPayload);

        if (request.delta() < 0) {
            checkLowStock(stock, request.storeId());
        }

        return InventoryMapper.toStockCheckItemResponse(stock, 0);
    }

    @Transactional
    public StockCheckResponse adjustStockBatch(StockAdjustBatchRequest request) {
        List<StockAdjustItemRequest> sortedItems = request.items().stream()
            .sorted(Comparator.comparing(StockAdjustItemRequest::productId))
            .toList();
        Set<UUID> seen = new HashSet<>();
        Map<UUID, StockItem> lockedStock = new LinkedHashMap<>();
        for (StockAdjustItemRequest item : sortedItems) {
            if (!seen.add(item.productId())) {
                throw new InvalidStockAdjustmentException("Duplicate productId in batch: " + item.productId());
            }
            lockedStock.put(item.productId(), lockStockItem(item.productId(), request.storeId()));
        }

        UUID actorId = resolveActorId();
        List<StockAdjustmentLog> logs = new ArrayList<>();
        for (StockAdjustItemRequest item : sortedItems) {
            StockItem stock = lockedStock.get(item.productId());
            int updatedOnHand = stock.getOnHand() + item.delta();
            if (updatedOnHand < 0) {
                throw new InvalidStockAdjustmentException("Resulting stock cannot be negative");
            }
            if (stock.getReserved() > updatedOnHand) {
                throw new InvalidStockAdjustmentException("Resulting stock cannot be less than reserved quantity");
            }
            stock.setOnHand(updatedOnHand);
            StockAdjustmentLog log = new StockAdjustmentLog();
            log.setProductId(item.productId());
            log.setStoreId(request.storeId());
            log.setDelta(item.delta());
            log.setReason(request.reason());
            log.setReferenceId(request.referenceId());
            log.setActorId(actorId);
            logs.add(log);
        }

        stockItemRepository.saveAll(lockedStock.values());
        stockAdjustmentLogRepository.saveAll(logs);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("storeId", request.storeId());
        details.put("reason", request.reason());
        details.put("itemCount", sortedItems.size());
        if (request.referenceId() != null) {
            details.put("referenceId", request.referenceId());
        }
        List<Map<String, Object>> itemDetails = sortedItems.stream()
            .map(item -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("productId", item.productId().toString());
                m.put("delta", item.delta());
                return m;
            })
            .toList();
        details.put("items", itemDetails);
        String entityId = request.referenceId() != null ? request.referenceId() : request.storeId();
        auditLogService.log(actorId, "STOCK_ADJUSTED_BATCH", "StockAdjustmentBatch", entityId, details);

        List<StockCheckItemResponse> responses = new ArrayList<>();
        for (StockAdjustItemRequest item : sortedItems) {
            StockItem stock = lockedStock.get(item.productId());
            Map<String, Object> eventPayload = new LinkedHashMap<>();
            eventPayload.put("productId", item.productId().toString());
            eventPayload.put("storeId", request.storeId());
            eventPayload.put("delta", item.delta());
            eventPayload.put("reason", request.reason());
            if (request.referenceId() != null) {
                eventPayload.put("referenceId", request.referenceId());
            }
            eventPayload.put("newOnHand", stock.getOnHand());
            eventPayload.put("adjustedAt", Instant.now().toString());
            outboxService.publish("StockItem", item.productId().toString(),
                "StockAdjusted", eventPayload);
            if (item.delta() < 0) {
                checkLowStock(stock, request.storeId());
            }
            responses.add(InventoryMapper.toStockCheckItemResponse(stock, 0));
        }

        return new StockCheckResponse(responses);
    }

    void checkLowStock(StockItem stock, String storeId) {
        int available = stock.getOnHand() - stock.getReserved();
        int threshold = inventoryProperties.getLowStockThreshold();
        if (available <= threshold) {
            Map<String, Object> alertPayload = new LinkedHashMap<>();
            alertPayload.put("productId", stock.getProductId().toString());
            alertPayload.put("warehouseId", storeId);
            alertPayload.put("currentQuantity", available);
            alertPayload.put("threshold", threshold);
            alertPayload.put("detectedAt", Instant.now().toString());
            outboxService.publish("StockItem", stock.getProductId().toString(),
                "LowStockAlert", alertPayload);
        }
    }

    private UUID resolveActorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        String principal = String.valueOf(authentication.getPrincipal());
        if (principal == null || principal.isBlank() || "anonymousUser".equalsIgnoreCase(principal)) {
            return null;
        }
        try {
            return UUID.fromString(principal);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private StockItem lockStockItem(UUID productId, String storeId) {
        try {
            return entityManager.createQuery(
                    "SELECT s FROM StockItem s WHERE s.productId = :pid AND s.storeId = :sid",
                    StockItem.class)
                .setParameter("pid", productId)
                .setParameter("sid", storeId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .setHint(LOCK_TIMEOUT_HINT, inventoryProperties.getLockTimeoutMs())
                .getSingleResult();
        } catch (NoResultException ex) {
            throw new ProductNotFoundException(productId, storeId);
        }
    }
}
