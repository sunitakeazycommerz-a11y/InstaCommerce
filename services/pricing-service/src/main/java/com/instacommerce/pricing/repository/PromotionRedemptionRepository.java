package com.instacommerce.pricing.repository;

import com.instacommerce.pricing.domain.PromotionRedemption;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PromotionRedemptionRepository extends JpaRepository<PromotionRedemption, UUID> {

    long countByPromotionIdAndUserId(UUID promotionId, UUID userId);

    Optional<PromotionRedemption> findByPromotionIdAndUserIdAndOrderId(
            UUID promotionId, UUID userId, UUID orderId);

    Optional<PromotionRedemption> findTopByPromotionIdAndUserIdOrderByRedeemedAtDesc(
            UUID promotionId, UUID userId);

    @Query("""
        SELECT pr.promotionId, COUNT(pr)
        FROM PromotionRedemption pr
        WHERE pr.userId = :userId
          AND pr.promotionId IN :promotionIds
        GROUP BY pr.promotionId
        """)
    List<Object[]> countByUserIdAndPromotionIds(
            @Param("userId") UUID userId,
            @Param("promotionIds") Collection<UUID> promotionIds);
}
