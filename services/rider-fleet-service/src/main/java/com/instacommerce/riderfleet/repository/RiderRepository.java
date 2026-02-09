package com.instacommerce.riderfleet.repository;

import com.instacommerce.riderfleet.domain.model.Rider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RiderRepository extends JpaRepository<Rider, UUID> {

    Optional<Rider> findByPhone(String phone);

    @Query("""
        SELECT r FROM Rider r
        WHERE r.status = com.instacommerce.riderfleet.domain.model.RiderStatus.ACTIVE
          AND r.storeId = :storeId
        """)
    List<Rider> findAvailableRidersByStoreId(@Param("storeId") UUID storeId);
}
