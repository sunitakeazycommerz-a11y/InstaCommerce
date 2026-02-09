package com.instacommerce.fulfillment.repository;

import com.instacommerce.fulfillment.domain.model.PickItem;
import com.instacommerce.fulfillment.domain.model.PickItemStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PickItemRepository extends JpaRepository<PickItem, UUID> {
    List<PickItem> findByPickTask_Id(UUID pickTaskId);

    Optional<PickItem> findByPickTask_OrderIdAndProductId(UUID orderId, UUID productId);

    long countByPickTask_IdAndStatus(UUID pickTaskId, PickItemStatus status);
}
