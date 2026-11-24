package com.instacommerce.riderfleet.repository;

import com.instacommerce.riderfleet.domain.model.RiderAvailability;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RiderAvailabilityRepository extends JpaRepository<RiderAvailability, UUID> {

    Optional<RiderAvailability> findByRiderId(UUID riderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ra FROM RiderAvailability ra WHERE ra.riderId = :riderId")
    Optional<RiderAvailability> findByRiderIdForUpdate(@Param("riderId") UUID riderId);

    @Query(value = """
        SELECT * FROM rider_availability
        WHERE rider_id = :riderId
          AND is_available = true
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    Optional<RiderAvailability> findAvailableByRiderIdForUpdateSkipLocked(@Param("riderId") UUID riderId);

    List<RiderAvailability> findByIsAvailableTrueAndStoreId(UUID storeId);

    List<RiderAvailability> findByStoreIdAndIsAvailableTrueAndRiderIdIn(UUID storeId, List<UUID> riderIds);

    List<RiderAvailability> findByIsAvailableTrueAndRiderIdIn(List<UUID> riderIds);

    @Query(value = """
        SELECT ra.* FROM rider_availability ra
        JOIN riders r ON r.id = ra.rider_id
        WHERE ra.is_available = true
          AND ra.store_id = :storeId
          AND r.status = 'ACTIVE'
          AND (:minWeightGrams <= 0
               OR COALESCE(r.max_capacity_grams, CASE r.vehicle_type
                      WHEN 'CAR' THEN 100000
                      WHEN 'MOTORCYCLE' THEN 25000
                      ELSE 8000
                  END) >= :minWeightGrams)
          AND (:maxConsecutive <= 0
               OR r.consecutive_deliveries < :maxConsecutive
               OR (:fatigueResetMinutes > 0 AND (r.last_delivery_completed_at IS NULL
                   OR r.last_delivery_completed_at <= :fatigueCutoff)))
          AND (:enforceShift = false OR EXISTS (
               SELECT 1 FROM rider_shifts rs
               WHERE rs.rider_id = r.id
                 AND rs.status = 'ACTIVE'
                 AND rs.shift_start <= :now
                 AND rs.shift_end > :now
          ))
          AND ra.current_lat IS NOT NULL
          AND ra.current_lng IS NOT NULL
          AND (
            6371 * acos(
              LEAST(1.0, GREATEST(-1.0,
                cos(radians(:lat)) * cos(radians(ra.current_lat))
                * cos(radians(ra.current_lng) - radians(:lng))
                + sin(radians(:lat)) * sin(radians(ra.current_lat))
              ))
            )
          ) <= :radiusKm
        ORDER BY
          (6371 * acos(
            LEAST(1.0, GREATEST(-1.0,
              cos(radians(:lat)) * cos(radians(ra.current_lat))
              * cos(radians(ra.current_lng) - radians(:lng))
              + sin(radians(:lat)) * sin(radians(ra.current_lat))
            ))
          )) ASC,
          r.rating_avg DESC
        LIMIT :candidateLimit
        """, nativeQuery = true)
    List<RiderAvailability> findEligibleNearestCandidates(
        @Param("lat") BigDecimal lat,
        @Param("lng") BigDecimal lng,
        @Param("radiusKm") double radiusKm,
        @Param("storeId") UUID storeId,
        @Param("minWeightGrams") int minWeightGrams,
        @Param("maxConsecutive") int maxConsecutive,
        @Param("fatigueResetMinutes") int fatigueResetMinutes,
        @Param("fatigueCutoff") Instant fatigueCutoff,
        @Param("enforceShift") boolean enforceShift,
        @Param("now") Instant now,
        @Param("candidateLimit") int candidateLimit
    );
}
