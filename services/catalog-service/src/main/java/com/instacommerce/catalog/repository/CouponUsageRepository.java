package com.instacommerce.catalog.repository;

import com.instacommerce.catalog.domain.model.CouponUsage;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponUsageRepository extends JpaRepository<CouponUsage, UUID> {
    long countByCoupon_Id(UUID couponId);

    long countByCoupon_IdAndUserId(UUID couponId, UUID userId);

    boolean existsByUserId(UUID userId);
}
