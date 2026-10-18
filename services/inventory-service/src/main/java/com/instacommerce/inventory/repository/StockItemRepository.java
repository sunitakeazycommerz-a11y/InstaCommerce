package com.instacommerce.inventory.repository;

import com.instacommerce.inventory.domain.model.StockItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockItemRepository extends JpaRepository<StockItem, UUID> {
    Optional<StockItem> findByProductIdAndStoreId(UUID productId, UUID storeId);

    Optional<StockItem> findByStoreIdAndProductId(UUID storeId, UUID productId);

    List<StockItem> findByStoreIdAndProductIdIn(UUID storeId, List<UUID> productIds);

    List<StockItem> findByStoreId(UUID storeId);
}
