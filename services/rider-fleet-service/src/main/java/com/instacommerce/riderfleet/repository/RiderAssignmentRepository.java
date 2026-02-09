package com.instacommerce.riderfleet.repository;

import com.instacommerce.riderfleet.domain.model.RiderAssignment;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiderAssignmentRepository extends JpaRepository<RiderAssignment, UUID> {
    boolean existsByOrderId(UUID orderId);
}
