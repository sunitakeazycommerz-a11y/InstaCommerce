package com.instacommerce.catalog.service;

import com.instacommerce.catalog.domain.model.Coupon;
import com.instacommerce.catalog.dto.response.CouponDiscountResponse;
import com.instacommerce.catalog.exception.InvalidCouponException;
import com.instacommerce.catalog.repository.CouponRepository;
import com.instacommerce.catalog.repository.CouponUsageRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CouponService {
    private final CouponRepository couponRepository;
    private final CouponUsageRepository couponUsageRepository;

    public CouponService(CouponRepository couponRepository, CouponUsageRepository couponUsageRepository) {
        this.couponRepository = couponRepository;
        this.couponUsageRepository = couponUsageRepository;
    }

    public CouponDiscountResponse validateAndCalculate(String code, UUID userId, long subtotalCents) {
        if (code == null || code.isBlank()) {
            return null;
        }
        Coupon coupon = couponRepository.findByCodeIgnoreCase(code)
            .orElseThrow(() -> new InvalidCouponException("Coupon not found"));
        if (!coupon.isActive()) {
            throw new InvalidCouponException("Coupon is inactive");
        }
        Instant now = Instant.now();
        if ((coupon.getValidFrom() != null && now.isBefore(coupon.getValidFrom()))
            || (coupon.getValidTo() != null && now.isAfter(coupon.getValidTo()))) {
            throw new InvalidCouponException("Coupon has expired");
        }
        long minOrder = coupon.getMinOrderCents() == null ? 0 : coupon.getMinOrderCents();
        if (subtotalCents < minOrder) {
            throw new InvalidCouponException("Minimum order value not met");
        }
        if (coupon.getUsageLimit() != null) {
            long totalUsage = couponUsageRepository.countByCoupon_Id(coupon.getId());
            if (totalUsage >= coupon.getUsageLimit()) {
                throw new InvalidCouponException("Coupon usage limit reached");
            }
        }
        if (coupon.getPerUserLimit() != null && userId != null) {
            long userUsage = couponUsageRepository.countByCoupon_IdAndUserId(coupon.getId(), userId);
            if (userUsage >= coupon.getPerUserLimit()) {
                throw new InvalidCouponException("Coupon usage limit reached for user");
            }
        }
        if (coupon.isFirstOrderOnly() && userId != null && couponUsageRepository.existsByUserId(userId)) {
            throw new InvalidCouponException("Coupon is valid for first order only");
        }
        long discount = computeDiscount(coupon, subtotalCents);
        return new CouponDiscountResponse(coupon.getCode(), discount, true);
    }

    private long computeDiscount(Coupon coupon, long subtotalCents) {
        long discount;
        if (coupon.getDiscountType() == Coupon.DiscountType.PERCENTAGE) {
            discount = subtotalCents * coupon.getDiscountValue() / 10_000;
            if (coupon.getMaxDiscountCents() != null) {
                discount = Math.min(discount, coupon.getMaxDiscountCents());
            }
        } else {
            discount = coupon.getDiscountValue();
        }
        return Math.min(discount, subtotalCents);
    }
}
