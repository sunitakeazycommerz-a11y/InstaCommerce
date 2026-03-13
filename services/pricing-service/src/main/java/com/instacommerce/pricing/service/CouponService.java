package com.instacommerce.pricing.service;

import com.instacommerce.pricing.domain.Coupon;
import com.instacommerce.pricing.domain.CouponRedemption;
import com.instacommerce.pricing.domain.Promotion;
import com.instacommerce.pricing.dto.request.CreateCouponRequest;
import com.instacommerce.pricing.dto.response.CouponResponse;
import com.instacommerce.pricing.exception.CouponNotFoundException;
import com.instacommerce.pricing.exception.InvalidCouponException;
import com.instacommerce.pricing.exception.PromotionNotFoundException;
import com.instacommerce.pricing.repository.CouponRedemptionRepository;
import com.instacommerce.pricing.repository.CouponRepository;
import com.instacommerce.pricing.repository.PromotionRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CouponService {
    private static final Logger log = LoggerFactory.getLogger(CouponService.class);

    private final CouponRepository couponRepository;
    private final CouponRedemptionRepository couponRedemptionRepository;
    private final PromotionRepository promotionRepository;
    private final PromotionService promotionService;

    public CouponService(CouponRepository couponRepository,
                         CouponRedemptionRepository couponRedemptionRepository,
                         PromotionRepository promotionRepository,
                         PromotionService promotionService) {
        this.couponRepository = couponRepository;
        this.couponRedemptionRepository = couponRedemptionRepository;
        this.promotionRepository = promotionRepository;
        this.promotionService = promotionService;
    }

    public record CouponValidationResult(long discountCents, String couponCode) {}

    @Transactional(readOnly = true)
    public CouponValidationResult validateCoupon(String code, UUID userId, long cartTotalCents) {
        Coupon coupon = couponRepository.findByCodeIgnoreCase(code)
                .orElseThrow(() -> new CouponNotFoundException(code));

        if (!coupon.isActive()) {
            throw new InvalidCouponException("Coupon is no longer active");
        }

        Promotion promotion = coupon.getPromotion();
        Instant now = Instant.now();
        if (!promotion.isActive() || now.isBefore(promotion.getStartAt()) || now.isAfter(promotion.getEndAt())) {
            throw new InvalidCouponException("Coupon promotion has expired or is inactive");
        }

        if (cartTotalCents < promotion.getMinOrderCents()) {
            throw new InvalidCouponException("Minimum order amount not met");
        }

        // Check total limit
        if (coupon.getTotalLimit() != null && coupon.getTotalRedeemed() >= coupon.getTotalLimit()) {
            throw new InvalidCouponException("Coupon has reached its total redemption limit");
        }

        // Check per-user limit
        long userRedemptions = couponRedemptionRepository.countByCouponIdAndUserId(coupon.getId(), userId);
        if (userRedemptions >= coupon.getPerUserLimit()) {
            throw new InvalidCouponException("You have already used this coupon the maximum number of times");
        }

        long discount = promotionService.calculateDiscount(promotion, cartTotalCents);
        return new CouponValidationResult(discount, code);
    }

    @Transactional
    public void redeemCoupon(String code, UUID userId, UUID orderId, long discountCents) {
        Coupon coupon = couponRepository.findByCodeIgnoreCaseForUpdate(code)
                .orElseThrow(() -> new CouponNotFoundException(code));

        // Thread-safe redemption: lock coupon row and check limits inside transaction
        if (coupon.getTotalLimit() != null && coupon.getTotalRedeemed() >= coupon.getTotalLimit()) {
            throw new InvalidCouponException("Coupon has reached its total redemption limit");
        }

        long userRedemptions = couponRedemptionRepository.countByCouponIdAndUserId(coupon.getId(), userId);
        if (userRedemptions >= coupon.getPerUserLimit()) {
            throw new InvalidCouponException("You have already used this coupon the maximum number of times");
        }

        CouponRedemption redemption = new CouponRedemption();
        redemption.setCoupon(coupon);
        redemption.setUserId(userId);
        redemption.setOrderId(orderId);
        redemption.setDiscountCents(discountCents);
        redemption.setSingleUse(coupon.isSingleUse());

        try {
            couponRedemptionRepository.save(redemption);
        } catch (DataIntegrityViolationException ex) {
            // Constraint violation for single-use coupon redemptions
            throw new InvalidCouponException("Coupon has already been used");
        }

        // Increment counter atomically
        coupon.setTotalRedeemed(coupon.getTotalRedeemed() + 1);
        couponRepository.save(coupon);
        promotionService.recordPromotionUsage(coupon.getPromotion().getId());

        log.info("Redeemed coupon code={} userId={} orderId={} discount={}", code, userId, orderId, discountCents);
    }

    @Transactional
    public void unredeemCoupon(String code, UUID userId, UUID orderId) {
        Coupon coupon = couponRepository.findByCodeIgnoreCaseForUpdate(code)
                .orElseThrow(() -> new CouponNotFoundException(code));

        couponRedemptionRepository.findByCouponIdAndUserIdAndOrderId(coupon.getId(), userId, orderId)
                .ifPresent(redemption -> {
                    couponRedemptionRepository.delete(redemption);
                    if (coupon.getTotalRedeemed() > 0) {
                        coupon.setTotalRedeemed(coupon.getTotalRedeemed() - 1);
                        couponRepository.save(coupon);
                    }
                    promotionService.rollbackPromotionUsage(coupon.getPromotion().getId());
                    log.info("Un-redeemed coupon code={} userId={} orderId={}", code, userId, orderId);
                });
    }

    @Transactional
    public CouponResponse create(CreateCouponRequest request) {
        Promotion promotion = promotionRepository.findById(request.promotionId())
                .orElseThrow(() -> new PromotionNotFoundException(request.promotionId().toString()));

        Coupon coupon = new Coupon();
        coupon.setCode(request.code().toUpperCase());
        coupon.setPromotion(promotion);
        coupon.setSingleUse(request.singleUse());
        coupon.setPerUserLimit(request.perUserLimit() > 0 ? request.perUserLimit() : 1);
        coupon.setTotalLimit(request.totalLimit());
        coupon.setActive(true);

        Coupon saved = couponRepository.save(coupon);
        log.info("Created coupon id={} code={}", saved.getId(), saved.getCode());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public CouponResponse getByCode(String code) {
        Coupon coupon = couponRepository.findByCodeIgnoreCase(code)
                .orElseThrow(() -> new CouponNotFoundException(code));
        return toResponse(coupon);
    }

    @Transactional(readOnly = true)
    public List<CouponResponse> listAll() {
        return couponRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public void deactivate(UUID id) {
        Coupon coupon = couponRepository.findById(id)
                .orElseThrow(() -> new CouponNotFoundException(id.toString()));
        coupon.setActive(false);
        couponRepository.save(coupon);
        log.info("Deactivated coupon id={} code={}", id, coupon.getCode());
    }

    private CouponResponse toResponse(Coupon coupon) {
        return new CouponResponse(
                coupon.getId(),
                coupon.getCode(),
                coupon.getPromotion().getId(),
                coupon.getPromotion().getName(),
                coupon.isSingleUse(),
                coupon.getPerUserLimit(),
                coupon.getTotalLimit(),
                coupon.getTotalRedeemed(),
                coupon.isActive());
    }
}
