package com.instacommerce.catalog.repository;

import com.instacommerce.catalog.domain.model.Coupon;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponRepository extends JpaRepository<Coupon, UUID> {
    Optional<Coupon> findByCodeIgnoreCase(String code);
}
