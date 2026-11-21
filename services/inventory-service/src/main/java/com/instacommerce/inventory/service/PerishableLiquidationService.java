package com.instacommerce.inventory.service;

import com.instacommerce.inventory.domain.model.StockBatch;
import com.instacommerce.inventory.domain.model.StockItem;
import com.instacommerce.inventory.dto.request.CreateBatchRequest;
import com.instacommerce.inventory.dto.response.StockBatchResponse;
import com.instacommerce.inventory.repository.StockBatchRepository;
import com.instacommerce.inventory.repository.StockItemRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PerishableLiquidationService {
    private static final Logger log = LoggerFactory.getLogger(PerishableLiquidationService.class);
    private static final Duration DEFAULT_EXPIRY_WINDOW = Duration.ofHours(24);

    private final StockBatchRepository batchRepository;
    private final StockItemRepository stockItemRepository;
    private final OutboxService outboxService;

    public PerishableLiquidationService(StockBatchRepository batchRepository,
                                        StockItemRepository stockItemRepository,
                                        OutboxService outboxService) {
        this.batchRepository = batchRepository;
        this.stockItemRepository = stockItemRepository;
        this.outboxService = outboxService;
    }

    @Transactional
    public StockBatchResponse createOrUpdateBatch(UUID storeId, CreateBatchRequest request) {
        StockBatch batch = batchRepository.findByStoreIdAndProductIdAndBatchNumber(
            storeId, request.productId(), request.batchNumber())
            .orElseGet(() -> {
                StockBatch newBatch = new StockBatch();
                newBatch.setStoreId(storeId);
                newBatch.setProductId(request.productId());
                newBatch.setBatchNumber(request.batchNumber());
                return newBatch;
            });

        batch.setQuantity(request.quantity());
        batch.setExpiryDate(request.expiryDate());
        batch = batchRepository.save(batch);

        // Ensure stock item exists in store
        StockItem stockItem = stockItemRepository.findByStoreIdAndProductId(storeId, request.productId())
            .orElseGet(() -> {
                StockItem item = new StockItem();
                item.setStoreId(storeId);
                item.setProductId(request.productId());
                item.setOnHand(0);
                item.setReserved(0);
                return item;
            });
        stockItemRepository.save(stockItem);

        return toResponse(batch);
    }

    @Transactional
    public List<StockBatchResponse> triggerMarkdownsForStore(UUID storeId, Duration window) {
        Duration lookahead = window != null ? window : DEFAULT_EXPIRY_WINDOW;
        Instant cutoffTime = Instant.now().plus(lookahead);
        List<StockBatch> expiringBatches = batchRepository
            .findByStoreIdAndExpiryDateLessThanEqualAndIsDiscountedFalseAndQuantityGreaterThan(storeId, cutoffTime, 0);

        return processMarkdowns(expiringBatches);
    }

    @Transactional
    public List<StockBatchResponse> triggerMarkdownsGlobal(Duration window) {
        Duration lookahead = window != null ? window : DEFAULT_EXPIRY_WINDOW;
        Instant cutoffTime = Instant.now().plus(lookahead);
        List<StockBatch> expiringBatches = batchRepository
            .findByExpiryDateLessThanEqualAndIsDiscountedFalseAndQuantityGreaterThan(cutoffTime, 0);

        return processMarkdowns(expiringBatches);
    }

    private List<StockBatchResponse> processMarkdowns(List<StockBatch> expiringBatches) {
        List<StockBatchResponse> processed = new ArrayList<>();
        Instant now = Instant.now();

        for (StockBatch batch : expiringBatches) {
            batch.setDiscounted(true);
            batch.setDiscountPercent(50); // 50% discount per Issue #583
            batch.setMarkdownTriggeredAt(now);
            batch = batchRepository.save(batch);

            // Emit PerishableExpiringSoon event to pricing-service & catalog
            Map<String, Object> payload = new HashMap<>();
            payload.put("store_id", batch.getStoreId().toString());
            payload.put("product_id", batch.getProductId().toString());
            payload.put("batch_number", batch.getBatchNumber());
            payload.put("quantity", batch.getQuantity());
            payload.put("expiry_date", batch.getExpiryDate().toString());
            payload.put("discount_percent", 50);
            payload.put("pricing_rule", "AUTOMATED_PERISHABLE_MARKDOWN");
            payload.put("category", "SAVE_FOOD_QUICK_SALE");
            payload.put("featured_section", "Save Food / Quick Sale");
            payload.put("timestamp", now.toString());

            outboxService.publish("STOCK_BATCH", batch.getId().toString(), "PerishableExpiringSoon", payload);

            log.info("Triggered automated 50% markdown for expiring perishable batch: store={} sku={} batch={} qty={}",
                batch.getStoreId(), batch.getProductId(), batch.getBatchNumber(), batch.getQuantity());

            processed.add(toResponse(batch));
        }

        return processed;
    }

    @Transactional(readOnly = true)
    public List<StockBatchResponse> getStoreBatches(UUID storeId) {
        return batchRepository.findByStoreId(storeId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public List<StockBatchResponse> writeOffExpiredBatches(UUID storeId) {
        Instant now = Instant.now();
        List<UUID> productIds = batchRepository.findExpiredBatchesForWriteOff(storeId, now).stream()
            .map(StockBatch::getProductId)
            .distinct()
            .sorted()
            .toList();
        List<StockBatchResponse> writtenOff = new ArrayList<>();
        for (UUID productId : productIds) {
            Optional<StockItem> stockOpt = stockItemRepository.findByProductIdAndStoreIdForUpdate(productId, storeId);
            if (stockOpt == null || stockOpt.isEmpty()) {
                log.warn("Skipping expired batch write-off for missing stock item: store={} product={}", storeId, productId);
                continue;
            }
            StockItem stock = stockOpt.get();
            int remainingDeductible = Math.max(0, stock.getOnHand() - stock.getReserved());
            if (remainingDeductible <= 0) {
                continue;
            }

            List<StockBatch> lockedBatches = batchRepository.findExpiredBatchesForProductUpdate(storeId, productId, now);
            int totalWrittenOffForProduct = 0;
            for (StockBatch batch : lockedBatches) {
                int expiredQty = batch.getQuantity();
                if (expiredQty <= 0 || remainingDeductible <= 0) {
                    continue;
                }
                int writeOffQty = Math.min(expiredQty, remainingDeductible);
                batch.setQuantity(expiredQty - writeOffQty);
                StockBatch savedBatch = batchRepository.save(batch);

                remainingDeductible -= writeOffQty;
                totalWrittenOffForProduct += writeOffQty;

                Map<String, Object> payload = new HashMap<>();
                payload.put("store_id", storeId.toString());
                payload.put("product_id", productId.toString());
                payload.put("batch_number", savedBatch.getBatchNumber());
                payload.put("written_off_quantity", writeOffQty);
                payload.put("expiry_date", savedBatch.getExpiryDate().toString());
                payload.put("timestamp", now.toString());
                outboxService.publish("STOCK_BATCH", savedBatch.getId().toString(), "ExpiredStockWrittenOff", payload);

                writtenOff.add(toResponse(savedBatch));
            }
            if (totalWrittenOffForProduct > 0) {
                stock.setOnHand(stock.getOnHand() - totalWrittenOffForProduct);
                stockItemRepository.save(stock);
            }
        }
        return writtenOff;
    }

    private StockBatchResponse toResponse(StockBatch batch) {
        return new StockBatchResponse(
            batch.getId(),
            batch.getStoreId(),
            batch.getProductId(),
            batch.getBatchNumber(),
            batch.getQuantity(),
            batch.getExpiryDate(),
            batch.isDiscounted(),
            batch.getDiscountPercent(),
            batch.getMarkdownTriggeredAt()
        );
    }
}
