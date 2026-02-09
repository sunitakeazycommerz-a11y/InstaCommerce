package com.instacommerce.fulfillment.repository;

import com.instacommerce.fulfillment.domain.model.PickTask;
import com.instacommerce.fulfillment.domain.model.PickTaskStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PickTaskRepository extends JpaRepository<PickTask, UUID> {
    Optional<PickTask> findByOrderId(UUID orderId);

    List<PickTask> findByStoreIdAndStatusIn(String storeId, List<PickTaskStatus> statuses);

    Page<PickTask> findByStoreIdAndStatusIn(String storeId, List<PickTaskStatus> statuses, Pageable pageable);

    @Modifying
    @Query("update PickTask p set p.userId = :placeholder, p.userErased = true where p.userId = :userId")
    int anonymizeByUserId(@Param("userId") UUID userId, @Param("placeholder") UUID placeholder);
}
