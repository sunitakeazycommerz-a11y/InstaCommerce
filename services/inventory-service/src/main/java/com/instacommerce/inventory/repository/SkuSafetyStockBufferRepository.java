package com.instacommerce.inventory.repository;

import com.instacommerce.inventory.domain.model.SkuSafetyStockBuffer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SkuSafetyStockBufferRepository extends JpaRepository<SkuSafetyStockBuffer, UUID> {
    Optional<SkuSafetyStockBuffer> findByStoreIdAndProductId(UUID storeId, UUID productId);

    List<SkuSafetyStockBuffer> findByStoreId(UUID storeId);
}
