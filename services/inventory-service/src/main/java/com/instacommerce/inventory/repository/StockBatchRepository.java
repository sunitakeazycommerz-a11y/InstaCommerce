package com.instacommerce.inventory.repository;

import com.instacommerce.inventory.domain.model.StockBatch;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface StockBatchRepository extends JpaRepository<StockBatch, UUID> {
    Optional<StockBatch> findByStoreIdAndProductIdAndBatchNumber(UUID storeId, UUID productId, String batchNumber);

    List<StockBatch> findByStoreIdAndProductId(UUID storeId, UUID productId);

    boolean existsByStoreIdAndProductId(UUID storeId, UUID productId);

    List<StockBatch> findByStoreId(UUID storeId);

    List<StockBatch> findByExpiryDateLessThanEqualAndIsDiscountedFalseAndQuantityGreaterThan(Instant cutoffTime, int minQuantity);

    List<StockBatch> findByStoreIdAndExpiryDateLessThanEqualAndIsDiscountedFalseAndQuantityGreaterThan(UUID storeId, Instant cutoffTime, int minQuantity);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "2000")})
    @Query("""
        SELECT b FROM StockBatch b
        WHERE b.storeId = :storeId
          AND b.productId = :productId
          AND b.quantity > 0
          AND b.expiryDate > :now
        ORDER BY b.expiryDate ASC, b.createdAt ASC
        """)
    List<StockBatch> findSellableBatchesForFefoUpdate(
        @Param("storeId") UUID storeId,
        @Param("productId") UUID productId,
        @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "2000")})
    @Query("""
        SELECT b FROM StockBatch b
        WHERE b.storeId = :storeId
          AND b.productId = :productId
          AND b.expiryDate > :now
        ORDER BY b.expiryDate DESC, b.createdAt DESC
        """)
    List<StockBatch> findActiveBatchesForRestoreUpdate(
        @Param("storeId") UUID storeId,
        @Param("productId") UUID productId,
        @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "2000")})
    @Query("""
        SELECT b FROM StockBatch b
        WHERE b.storeId = :storeId
          AND b.quantity > 0
          AND b.expiryDate <= :now
        ORDER BY b.productId ASC, b.expiryDate ASC
        """)
    List<StockBatch> findExpiredBatchesForWriteOff(
        @Param("storeId") UUID storeId,
        @Param("now") Instant now);
}
