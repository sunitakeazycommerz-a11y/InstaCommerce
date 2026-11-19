package com.instacommerce.fulfillment.repository;

import com.instacommerce.fulfillment.domain.model.PickBatch;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PickBatchRepository extends JpaRepository<PickBatch, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM PickBatch b WHERE b.id = :id")
    Optional<PickBatch> findByIdForUpdate(@Param("id") UUID id);

    List<PickBatch> findByStoreIdAndStatus(UUID storeId, String status);
    List<PickBatch> findByStoreId(UUID storeId);
}
