package com.instacommerce.order.repository;

import com.instacommerce.order.domain.model.Order;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, UUID> {
    Optional<Order> findByIdAndUserId(UUID id, UUID userId);

    Page<Order> findByUserId(UUID userId, Pageable pageable);

    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    @Modifying
    @Query("update Order o set o.userId = :placeholder, o.userErased = true where o.userId = :userId")
    int anonymizeByUserId(@Param("userId") UUID userId, @Param("placeholder") UUID placeholder);
}
