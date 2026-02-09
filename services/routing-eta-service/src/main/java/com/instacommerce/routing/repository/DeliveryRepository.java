package com.instacommerce.routing.repository;

import com.instacommerce.routing.domain.model.Delivery;
import com.instacommerce.routing.domain.model.DeliveryStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {

    Optional<Delivery> findByOrderId(UUID orderId);

    List<Delivery> findByRiderIdAndStatus(UUID riderId, DeliveryStatus status);
}
