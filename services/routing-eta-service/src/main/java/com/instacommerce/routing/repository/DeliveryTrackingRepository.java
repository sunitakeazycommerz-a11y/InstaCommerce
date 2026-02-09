package com.instacommerce.routing.repository;

import com.instacommerce.routing.domain.model.DeliveryTracking;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeliveryTrackingRepository extends JpaRepository<DeliveryTracking, UUID> {

    @Query("""
        SELECT t FROM DeliveryTracking t
        WHERE t.deliveryId = :deliveryId
        ORDER BY t.recordedAt DESC
        LIMIT 1
        """)
    Optional<DeliveryTracking> findLatestByDeliveryId(@Param("deliveryId") UUID deliveryId);

    List<DeliveryTracking> findByDeliveryIdOrderByRecordedAtDesc(UUID deliveryId);
}
