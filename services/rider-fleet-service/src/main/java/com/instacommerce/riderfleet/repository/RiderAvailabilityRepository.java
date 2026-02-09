package com.instacommerce.riderfleet.repository;

import com.instacommerce.riderfleet.domain.model.RiderAvailability;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RiderAvailabilityRepository extends JpaRepository<RiderAvailability, UUID> {

    Optional<RiderAvailability> findByRiderId(UUID riderId);

    List<RiderAvailability> findByIsAvailableTrueAndStoreId(UUID storeId);

    /**
     * Finds the nearest available riders within a given radius using the Haversine formula.
     * Uses pessimistic locking (FOR UPDATE SKIP LOCKED) to prevent double-assignment.
     * Results are ordered by distance ascending, then by rider rating descending.
     */
    @Query(value = """
        SELECT ra.* FROM rider_availability ra
        JOIN riders r ON r.id = ra.rider_id
        WHERE ra.is_available = true
          AND ra.store_id = :storeId
          AND r.status = 'ACTIVE'
          AND ra.current_lat IS NOT NULL
          AND ra.current_lng IS NOT NULL
          AND (
            6371 * acos(
              cos(radians(:lat)) * cos(radians(ra.current_lat))
              * cos(radians(ra.current_lng) - radians(:lng))
              + sin(radians(:lat)) * sin(radians(ra.current_lat))
            )
          ) <= :radiusKm
        ORDER BY
          (6371 * acos(
            cos(radians(:lat)) * cos(radians(ra.current_lat))
            * cos(radians(ra.current_lng) - radians(:lng))
            + sin(radians(:lat)) * sin(radians(ra.current_lat))
          )) ASC,
          r.rating_avg DESC
        LIMIT 1
        FOR UPDATE OF ra SKIP LOCKED
        """, nativeQuery = true)
    Optional<RiderAvailability> findNearestAvailable(
        @Param("lat") BigDecimal lat,
        @Param("lng") BigDecimal lng,
        @Param("radiusKm") double radiusKm,
        @Param("storeId") UUID storeId
    );
}
