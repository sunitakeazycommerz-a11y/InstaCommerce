package com.instacommerce.pricing.repository;

import com.instacommerce.pricing.domain.Coupon;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponRepository extends JpaRepository<Coupon, UUID> {

    Optional<Coupon> findByCodeIgnoreCase(String code);
}
