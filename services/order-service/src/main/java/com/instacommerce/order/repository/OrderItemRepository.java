package com.instacommerce.order.repository;

import com.instacommerce.order.domain.model.OrderItem;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {
}
