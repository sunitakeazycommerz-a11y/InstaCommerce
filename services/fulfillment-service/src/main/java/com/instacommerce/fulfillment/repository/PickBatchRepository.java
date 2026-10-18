package com.instacommerce.fulfillment.repository;

import com.instacommerce.fulfillment.domain.model.PickBatch;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PickBatchRepository extends JpaRepository<PickBatch, UUID> {
    List<PickBatch> findByStoreIdAndStatus(String storeId, String status);
    List<PickBatch> findByStoreId(String storeId);
}
