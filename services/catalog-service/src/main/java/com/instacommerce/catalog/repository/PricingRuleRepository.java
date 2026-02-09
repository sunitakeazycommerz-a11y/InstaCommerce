package com.instacommerce.catalog.repository;

import com.instacommerce.catalog.domain.model.PricingRule;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PricingRuleRepository extends JpaRepository<PricingRule, UUID> {
    @Query("""
        select r from PricingRule r
        where r.product.id = :productId
          and r.isActive = true
          and (:storeId is null or r.storeId is null or r.storeId = :storeId)
          and (:zoneId is null or r.zoneId is null or r.zoneId = :zoneId)
          and r.validFrom <= :now
          and (r.validTo is null or r.validTo >= :now)
        order by r.priority desc
        """)
    List<PricingRule> findApplicable(@Param("productId") UUID productId,
                                     @Param("storeId") String storeId,
                                     @Param("zoneId") String zoneId,
                                     @Param("now") Instant now);
}
