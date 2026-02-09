package com.instacommerce.riderfleet.repository;

import com.instacommerce.riderfleet.domain.model.RiderEarning;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RiderEarningRepository extends JpaRepository<RiderEarning, UUID> {

    List<RiderEarning> findByRiderIdAndEarnedAtBetween(UUID riderId, Instant from, Instant to);

    @Query("""
        SELECT COALESCE(SUM(e.deliveryFeeCents + e.tipCents + e.incentiveCents), 0)
        FROM RiderEarning e
        WHERE e.riderId = :riderId AND e.earnedAt BETWEEN :from AND :to
        """)
    long sumTotalEarnings(@Param("riderId") UUID riderId,
                          @Param("from") Instant from,
                          @Param("to") Instant to);
}
