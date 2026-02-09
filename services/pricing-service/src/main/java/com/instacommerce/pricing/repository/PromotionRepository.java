package com.instacommerce.pricing.repository;

import com.instacommerce.pricing.domain.Promotion;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PromotionRepository extends JpaRepository<Promotion, UUID> {

    @Query("""
        SELECT p FROM Promotion p
        WHERE p.active = true
          AND (p.maxUses IS NULL OR p.currentUses < p.maxUses)
        """)
    List<Promotion> findActivePromotions();
}
