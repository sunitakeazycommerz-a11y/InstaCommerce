package com.instacommerce.order.repository;

import com.instacommerce.order.domain.model.OrderStatusHistory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderStatusHistoryRepository extends JpaRepository<OrderStatusHistory, Long> {
    List<OrderStatusHistory> findByOrder_IdOrderByCreatedAtAsc(UUID orderId);
}
