package com.instacommerce.routing.repository;

import com.instacommerce.routing.domain.model.RoutingBarrier;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RoutingBarrierRepository extends JpaRepository<RoutingBarrier, UUID> {
    List<RoutingBarrier> findByIsActiveTrue();
}
