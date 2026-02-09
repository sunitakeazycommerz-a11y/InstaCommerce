package com.instacommerce.pricing.repository;

import com.instacommerce.pricing.domain.Promotion;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PromotionRepository extends JpaRepository<Promotion, UUID> {

    @Query("""
        SELECT p FROM Promotion p
        WHERE p.active = true
          AND p.startAt <= :now
          AND p.endAt > :now
          AND (p.maxUses IS NULL OR p.currentUses < p.maxUses)
        ORDER BY p.discountValue DESC
        """)
    List<Promotion> findActivePromotions(@Param("now") Instant now);
}
