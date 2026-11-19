package com.instacommerce.inventory.repository;

import com.instacommerce.inventory.domain.model.StockItem;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface StockItemRepository extends JpaRepository<StockItem, UUID> {
    Optional<StockItem> findByProductIdAndStoreId(UUID productId, UUID storeId);

    Optional<StockItem> findByStoreIdAndProductId(UUID storeId, UUID productId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "2000")})
    @Query("SELECT s FROM StockItem s WHERE s.productId = :productId AND s.storeId = :storeId")
    Optional<StockItem> findByProductIdAndStoreIdForUpdate(@Param("productId") UUID productId,
                                                           @Param("storeId") UUID storeId);

    List<StockItem> findByStoreIdAndProductIdIn(UUID storeId, List<UUID> productIds);

    List<StockItem> findByStoreId(UUID storeId);
}
