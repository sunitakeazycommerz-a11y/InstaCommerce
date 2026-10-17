package com.instacommerce.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.pricing.domain.Coupon;
import com.instacommerce.pricing.domain.CouponRedemption;
import com.instacommerce.pricing.domain.Promotion;
import com.instacommerce.pricing.exception.CouponNotFoundException;
import com.instacommerce.pricing.exception.InvalidCouponException;
import com.instacommerce.pricing.repository.CouponRedemptionRepository;
import com.instacommerce.pricing.repository.CouponRepository;
import com.instacommerce.pricing.repository.PromotionRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CouponServiceTest {

    @Mock private CouponRepository couponRepository;
    @Mock private CouponRedemptionRepository couponRedemptionRepository;
    @Mock private PromotionRepository promotionRepository;
    @Mock private PromotionService promotionService;

    private CouponService couponService;

    private static final String CODE = "SAVE20";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID PROMO_ID = UUID.randomUUID();
    private static final UUID COUPON_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        couponService = new CouponService(
                couponRepository,
                couponRedemptionRepository,
                promotionRepository,
                promotionService);
    }

    private Coupon createSampleCoupon(boolean active, int totalRedeemed, Integer totalLimit, int perUserLimit) {
        Promotion promotion = new Promotion();
        promotion.setId(PROMO_ID);
        promotion.setActive(true);
        promotion.setStartAt(Instant.now().minusSeconds(3600));
        promotion.setEndAt(Instant.now().plusSeconds(3600));
        promotion.setMinOrderCents(1000L);

        Coupon coupon = new Coupon();
        coupon.setId(COUPON_ID);
        coupon.setCode(CODE);
        coupon.setActive(active);
        coupon.setPromotion(promotion);
        coupon.setTotalRedeemed(totalRedeemed);
        coupon.setTotalLimit(totalLimit);
        coupon.setPerUserLimit(perUserLimit);
        return coupon;
    }

    @Test
    @DisplayName("validateCoupon succeeds when all constraints are met")
    void validateCouponSuccess() {
        Coupon coupon = createSampleCoupon(true, 5, 100, 2);
        when(couponRepository.findByCodeIgnoreCase(CODE)).thenReturn(Optional.of(coupon));
        when(couponRedemptionRepository.countByCouponIdAndUserId(COUPON_ID, USER_ID)).thenReturn(0L);
        when(promotionService.calculateDiscount(coupon.getPromotion(), 5000L)).thenReturn(1000L);

        CouponService.CouponValidationResult result = couponService.validateCoupon(CODE, USER_ID, 5000L);

        assertThat(result.couponCode()).isEqualTo(CODE);
        assertThat(result.discountCents()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("validateCoupon throws CouponNotFoundException for unknown coupon")
    void validateCouponNotFound() {
        when(couponRepository.findByCodeIgnoreCase("UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> couponService.validateCoupon("UNKNOWN", USER_ID, 5000L))
                .isInstanceOf(CouponNotFoundException.class);
    }

    @Test
    @DisplayName("validateCoupon throws InvalidCouponException when coupon is inactive")
    void validateCouponInactive() {
        Coupon coupon = createSampleCoupon(false, 0, 10, 1);
        when(couponRepository.findByCodeIgnoreCase(CODE)).thenReturn(Optional.of(coupon));

        assertThatThrownBy(() -> couponService.validateCoupon(CODE, USER_ID, 5000L))
                .isInstanceOf(InvalidCouponException.class)
                .hasMessageContaining("no longer active");
    }

    @Test
    @DisplayName("validateCoupon throws InvalidCouponException when per-user limit exceeded")
    void validateCouponPerUserLimitExceeded() {
        Coupon coupon = createSampleCoupon(true, 1, 10, 1);
        when(couponRepository.findByCodeIgnoreCase(CODE)).thenReturn(Optional.of(coupon));
        when(couponRedemptionRepository.countByCouponIdAndUserId(COUPON_ID, USER_ID)).thenReturn(1L);

        assertThatThrownBy(() -> couponService.validateCoupon(CODE, USER_ID, 5000L))
                .isInstanceOf(InvalidCouponException.class)
                .hasMessageContaining("maximum number of times");
    }

    @Test
    @DisplayName("redeemCoupon persists redemption and increments counter")
    void redeemCouponSuccess() {
        Coupon coupon = createSampleCoupon(true, 0, 10, 2);
        when(couponRepository.findByCodeIgnoreCaseForUpdate(CODE)).thenReturn(Optional.of(coupon));
        when(couponRedemptionRepository.countByCouponIdAndUserId(COUPON_ID, USER_ID)).thenReturn(0L);

        couponService.redeemCoupon(CODE, USER_ID, ORDER_ID, 1000L);

        verify(couponRedemptionRepository).save(any(CouponRedemption.class));
        assertThat(coupon.getTotalRedeemed()).isEqualTo(1);
        verify(couponRepository).save(coupon);
        verify(promotionService).recordPromotionUsage(PROMO_ID);
    }

    @Test
    @DisplayName("unredeemCoupon with orderId removes redemption, decrements total, and rolls back promo")
    void unredeemCouponWithOrderId() {
        Coupon coupon = createSampleCoupon(true, 1, 10, 2);
        CouponRedemption redemption = new CouponRedemption();
        redemption.setCoupon(coupon);
        redemption.setUserId(USER_ID);
        redemption.setOrderId(ORDER_ID);

        when(couponRepository.findByCodeIgnoreCaseForUpdate(CODE)).thenReturn(Optional.of(coupon));
        when(couponRedemptionRepository.findByCouponIdAndUserIdAndOrderId(COUPON_ID, USER_ID, ORDER_ID))
                .thenReturn(Optional.of(redemption));

        couponService.unredeemCoupon(CODE, USER_ID, ORDER_ID);

        verify(couponRedemptionRepository).delete(redemption);
        assertThat(coupon.getTotalRedeemed()).isEqualTo(0);
        verify(couponRepository).save(coupon);
        verify(promotionService).rollbackPromotionUsage(PROMO_ID);
    }

    @Test
    @DisplayName("rollbackUsage removes latest redemption and restores coupon availability without orderId")
    void rollbackUsageSuccess() {
        Coupon coupon = createSampleCoupon(true, 3, 10, 2);
        CouponRedemption redemption = new CouponRedemption();
        redemption.setCoupon(coupon);
        redemption.setUserId(USER_ID);
        redemption.setOrderId(ORDER_ID);

        when(couponRepository.findByCodeIgnoreCaseForUpdate(CODE)).thenReturn(Optional.of(coupon));
        when(couponRedemptionRepository.findTopByCouponIdAndUserIdOrderByRedeemedAtDesc(COUPON_ID, USER_ID))
                .thenReturn(Optional.of(redemption));

        couponService.rollbackUsage(CODE, USER_ID);

        verify(couponRedemptionRepository).delete(redemption);
        assertThat(coupon.getTotalRedeemed()).isEqualTo(2);
        verify(couponRepository).save(coupon);
        verify(promotionService).rollbackPromotionUsage(PROMO_ID);
    }

    @Test
    @DisplayName("rollbackUsage is idempotent when coupon or redemption does not exist")
    void rollbackUsageIdempotentWhenMissing() {
        Coupon coupon = createSampleCoupon(true, 0, 10, 2);
        when(couponRepository.findByCodeIgnoreCaseForUpdate(CODE)).thenReturn(Optional.of(coupon));
        when(couponRedemptionRepository.findTopByCouponIdAndUserIdOrderByRedeemedAtDesc(COUPON_ID, USER_ID))
                .thenReturn(Optional.empty());

        couponService.rollbackUsage(CODE, USER_ID);

        verify(couponRedemptionRepository, never()).delete(any());
        verify(couponRepository, never()).save(any());
        verify(promotionService, never()).rollbackPromotionUsage(any());
    }

    @Test
    @DisplayName("unredeemCoupon is graceful when coupon does not exist")
    void unredeemCouponNonExistentCoupon() {
        when(couponRepository.findByCodeIgnoreCaseForUpdate("NOTEXIST")).thenReturn(Optional.empty());

        couponService.unredeemCoupon("NOTEXIST", USER_ID, ORDER_ID);

        verify(couponRedemptionRepository, never()).delete(any());
        verify(couponRepository, never()).save(any());
    }
}
