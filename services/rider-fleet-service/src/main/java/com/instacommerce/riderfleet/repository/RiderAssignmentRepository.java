package com.instacommerce.riderfleet.repository;

import com.instacommerce.riderfleet.domain.model.RiderAssignment;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RiderAssignmentRepository extends JpaRepository<RiderAssignment, UUID> {
    boolean existsByOrderId(UUID orderId);

    Optional<RiderAssignment> findByOrderId(UUID orderId);

    @Modifying
    @Query("UPDATE RiderAssignment a SET a.completedAt = :completedAt WHERE a.orderId = :orderId AND a.completedAt IS NULL")
    int markCompletedIfPending(@Param("orderId") UUID orderId, @Param("completedAt") Instant completedAt);
}
