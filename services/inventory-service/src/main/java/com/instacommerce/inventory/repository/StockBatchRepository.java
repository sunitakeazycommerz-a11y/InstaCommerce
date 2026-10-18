package com.instacommerce.inventory.repository;

import com.instacommerce.inventory.domain.model.StockBatch;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StockBatchRepository extends JpaRepository<StockBatch, UUID> {
    Optional<StockBatch> findByStoreIdAndProductIdAndBatchNumber(UUID storeId, UUID productId, String batchNumber);

    List<StockBatch> findByStoreIdAndProductId(UUID storeId, UUID productId);

    List<StockBatch> findByStoreId(UUID storeId);

    List<StockBatch> findByExpiryDateLessThanEqualAndIsDiscountedFalseAndQuantityGreaterThan(Instant cutoffTime, int minQuantity);

    List<StockBatch> findByStoreIdAndExpiryDateLessThanEqualAndIsDiscountedFalseAndQuantityGreaterThan(UUID storeId, Instant cutoffTime, int minQuantity);
}
