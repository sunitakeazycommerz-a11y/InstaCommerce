package com.instacommerce.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.instacommerce.pricing.domain.Coupon;
import com.instacommerce.pricing.domain.Promotion;
import com.instacommerce.pricing.exception.InvalidCouponException;
import com.instacommerce.pricing.repository.CouponRedemptionRepository;
import com.instacommerce.pricing.repository.CouponRepository;
import com.instacommerce.pricing.repository.PromotionRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CouponConcurrencyTest {

    @Mock private CouponRepository couponRepository;
    @Mock private CouponRedemptionRepository couponRedemptionRepository;
    @Mock private PromotionRepository promotionRepository;
    @Mock private PromotionService promotionService;

    @Test
    @DisplayName("PromoReservationService atomic reservation prevents simultaneous exploit across 20 threads")
    void promoReservationServicePreventsConcurrentExploit() throws InterruptedException {
        PromoReservationService reservationService = new PromoReservationService();
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        UUID userId = UUID.randomUUID();
        String code = "REFERRAL20";

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    boolean acquired = reservationService.tryAcquire(code, userId, 300);
                    if (acquired) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown(); // Fire all 20 threads simultaneously
        doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successCount.get())
                .as("Exactly 1 thread must acquire the atomic reservation key")
                .isEqualTo(1);

        assertThat(failCount.get())
                .as("Remaining 19 threads must fail to acquire reservation")
                .isEqualTo(19);
    }

    @Test
    @DisplayName("CouponService.redeemCoupon rejects concurrent duplicate redemptions for same user")
    void couponServiceRejectsConcurrentDuplicateRedemptions() throws InterruptedException {
        PromoReservationService reservationService = new PromoReservationService();
        CouponService couponService = new CouponService(
                couponRepository,
                couponRedemptionRepository,
                promotionRepository,
                promotionService,
                null,
                reservationService
        );

        UUID userId = UUID.randomUUID();
        UUID promoId = UUID.randomUUID();
        UUID couponId = UUID.randomUUID();
        String code = "SAVE50";

        Promotion promotion = new Promotion();
        promotion.setId(promoId);
        promotion.setActive(true);
        promotion.setStartAt(Instant.now().minusSeconds(3600));
        promotion.setEndAt(Instant.now().plusSeconds(3600));
        promotion.setMinOrderCents(1000L);

        Coupon coupon = new Coupon();
        coupon.setId(couponId);
        coupon.setCode(code);
        coupon.setActive(true);
        coupon.setPromotion(promotion);
        coupon.setTotalRedeemed(0);
        coupon.setTotalLimit(100);
        coupon.setPerUserLimit(1);

        when(couponRepository.findByCodeIgnoreCaseForUpdate(code)).thenReturn(Optional.of(coupon));
        when(couponRedemptionRepository.countByCouponIdAndUserId(couponId, userId)).thenReturn(0L);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            UUID orderId = UUID.randomUUID();
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    couponService.redeemCoupon(code, userId, orderId, 500L);
                    successCount.incrementAndGet();
                } catch (Throwable t) {
                    exceptions.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successCount.get())
                .as("Only 1 concurrent redemption must succeed for perUserLimit=1")
                .isEqualTo(1);

        assertThat(exceptions)
                .hasSize(9)
                .allMatch(ex -> ex instanceof InvalidCouponException);
    }
}
