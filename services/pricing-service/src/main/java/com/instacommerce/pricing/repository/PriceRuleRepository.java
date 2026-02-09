package com.instacommerce.pricing.repository;

import com.instacommerce.pricing.domain.PriceRule;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PriceRuleRepository extends JpaRepository<PriceRule, UUID> {

    @Query("""
        SELECT r FROM PriceRule r
        WHERE r.productId = :productId
          AND r.active = true
          AND r.effectiveFrom <= :now
          AND (r.effectiveTo IS NULL OR r.effectiveTo > :now)
        ORDER BY r.effectiveFrom DESC
        LIMIT 1
        """)
    Optional<PriceRule> findActiveRuleByProductId(@Param("productId") UUID productId,
                                                  @Param("now") Instant now);
}
