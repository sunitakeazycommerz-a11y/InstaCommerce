package com.instacommerce.inventory.service;

import com.instacommerce.inventory.domain.model.StockBatch;
import com.instacommerce.inventory.domain.model.StockItem;
import com.instacommerce.inventory.exception.InsufficientStockException;
import com.instacommerce.inventory.repository.StockBatchRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class FefoBatchAllocationService {
    private final StockBatchRepository batchRepository;
    private final Clock clock;

    public FefoBatchAllocationService(StockBatchRepository batchRepository) {
        this(batchRepository, Clock.systemUTC());
    }

    @Autowired
    public FefoBatchAllocationService(StockBatchRepository batchRepository, Clock clock) {
        this.batchRepository = batchRepository;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public record BatchAllocation(String batchNumber, int quantity, Instant expiryDate) {}

    public int resolveSellableAvailable(StockItem stock) {
        int rawAvailable = Math.max(0, stock.getOnHand() - stock.getReserved());
        if (batchRepository == null) {
            return rawAvailable;
        }
        List<StockBatch> batches = batchRepository.findByStoreIdAndProductId(stock.getStoreId(), stock.getProductId());
        if (batches == null || batches.isEmpty()) {
            return rawAvailable;
        }
        Instant now = Instant.now(clock);
        int nonExpiredTotal = batches.stream()
            .filter(batch -> batch.getQuantity() > 0 && batch.getExpiryDate() != null && batch.getExpiryDate().isAfter(now))
            .mapToInt(StockBatch::getQuantity)
            .sum();
        int sellableOnHand = Math.min(stock.getOnHand(), nonExpiredTotal);
        return Math.max(0, sellableOnHand - stock.getReserved());
    }

    public List<BatchAllocation> allocateFefoForConfirm(UUID storeId, UUID productId, int quantity) {
        if (batchRepository == null || quantity <= 0 || !batchRepository.existsByStoreIdAndProductId(storeId, productId)) {
            return List.of();
        }
        Instant now = Instant.now(clock);
        List<StockBatch> sellableBatches = batchRepository.findSellableBatchesForFefoUpdate(storeId, productId, now);
        int totalNonExpired = sellableBatches.stream().mapToInt(StockBatch::getQuantity).sum();
        if (totalNonExpired < quantity) {
            throw new InsufficientStockException(productId, totalNonExpired, quantity);
        }

        int remaining = quantity;
        List<BatchAllocation> allocations = new ArrayList<>();
        List<StockBatch> modified = new ArrayList<>();
        for (StockBatch batch : sellableBatches) {
            if (remaining <= 0) {
                break;
            }
            int take = Math.min(batch.getQuantity(), remaining);
            if (take > 0) {
                batch.setQuantity(batch.getQuantity() - take);
                modified.add(batch);
                allocations.add(new BatchAllocation(batch.getBatchNumber(), take, batch.getExpiryDate()));
                remaining -= take;
            }
        }
        if (!modified.isEmpty()) {
            batchRepository.saveAll(modified);
        }
        return allocations;
    }

    public void restoreOnConfirmedCancel(UUID storeId, UUID productId, int quantity) {
        if (batchRepository == null || quantity <= 0 || !batchRepository.existsByStoreIdAndProductId(storeId, productId)) {
            return;
        }
        Instant now = Instant.now(clock);
        List<StockBatch> activeBatches = batchRepository.findActiveBatchesForRestoreUpdate(storeId, productId, now);
        if (!activeBatches.isEmpty()) {
            StockBatch target = activeBatches.get(0);
            target.setQuantity(target.getQuantity() + quantity);
            batchRepository.save(target);
        }
    }
}
