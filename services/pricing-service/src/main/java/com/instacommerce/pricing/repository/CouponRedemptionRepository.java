package com.instacommerce.pricing.repository;

import com.instacommerce.pricing.domain.CouponRedemption;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponRedemptionRepository extends JpaRepository<CouponRedemption, UUID> {

    long countByCouponIdAndUserId(UUID couponId, UUID userId);

    long countByCouponId(UUID couponId);
}
