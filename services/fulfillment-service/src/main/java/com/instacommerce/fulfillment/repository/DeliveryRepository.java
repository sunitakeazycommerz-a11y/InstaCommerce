package com.instacommerce.fulfillment.repository;

import com.instacommerce.fulfillment.domain.model.Delivery;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {
    Optional<Delivery> findByOrderId(UUID orderId);
}
