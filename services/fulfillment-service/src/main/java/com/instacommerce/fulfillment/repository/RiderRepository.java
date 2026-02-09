package com.instacommerce.fulfillment.repository;

import com.instacommerce.fulfillment.domain.model.Rider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RiderRepository extends JpaRepository<Rider, UUID> {
    List<Rider> findByStoreId(String storeId);

    @Query(value = """
        SELECT * FROM riders r
        WHERE r.store_id = :storeId AND r.is_available = true
        ORDER BY (
            SELECT MAX(d.dispatched_at) FROM deliveries d WHERE d.rider_id = r.id
        ) ASC NULLS FIRST
        LIMIT 1
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    Optional<Rider> findNextAvailableForStore(@Param("storeId") String storeId);
}
