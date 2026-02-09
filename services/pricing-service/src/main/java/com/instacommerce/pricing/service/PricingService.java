package com.instacommerce.pricing.service;

import com.instacommerce.pricing.domain.PriceRule;
import com.instacommerce.pricing.dto.request.CartItem;
import com.instacommerce.pricing.dto.request.PriceCalculationRequest;
import com.instacommerce.pricing.dto.response.PriceCalculationResponse;
import com.instacommerce.pricing.dto.response.PricedItem;
import com.instacommerce.pricing.exception.PriceRuleNotFoundException;
import com.instacommerce.pricing.repository.PriceRuleRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PricingService {
    private static final Logger log = LoggerFactory.getLogger(PricingService.class);

    private final PriceRuleRepository priceRuleRepository;
    private final PromotionService promotionService;
    private final CouponService couponService;

    public PricingService(PriceRuleRepository priceRuleRepository,
                          PromotionService promotionService,
                          CouponService couponService) {
        this.priceRuleRepository = priceRuleRepository;
        this.promotionService = promotionService;
        this.couponService = couponService;
    }

    @Cacheable(value = "productPrices", key = "#productId")
    @Transactional(readOnly = true)
    public long calculatePrice(UUID productId) {
        Instant now = Instant.now();
        PriceRule rule = priceRuleRepository.findActiveRuleByProductId(productId, now)
                .orElseThrow(() -> new PriceRuleNotFoundException(productId.toString()));
        return applyMultiplier(rule.getBasePriceCents(), rule.getMultiplier());
    }

    @Transactional(readOnly = true)
    public PriceCalculationResponse calculateCartPrice(PriceCalculationRequest request) {
        Instant now = Instant.now();
        List<PricedItem> pricedItems = new ArrayList<>();
        long subtotalCents = 0;

        for (CartItem item : request.items()) {
            PriceRule rule = priceRuleRepository.findActiveRuleByProductId(item.productId(), now)
                    .orElseThrow(() -> new PriceRuleNotFoundException(item.productId().toString()));

            long unitPrice = applyMultiplier(rule.getBasePriceCents(), rule.getMultiplier());
            long lineTotal = unitPrice * item.quantity();
            subtotalCents += lineTotal;

            pricedItems.add(new PricedItem(item.productId(), unitPrice, item.quantity(), lineTotal));
        }

        // Apply best applicable promotion
        List<String> appliedPromotions = new ArrayList<>();
        long promotionDiscount = 0;
        var applicablePromotions = promotionService.findApplicable(subtotalCents, now);
        if (!applicablePromotions.isEmpty()) {
            var bestPromo = applicablePromotions.get(0);
            promotionDiscount = promotionService.calculateDiscount(bestPromo, subtotalCents);
            appliedPromotions.add(bestPromo.getName());
        }

        // Apply coupon if provided
        long couponDiscount = 0;
        String appliedCoupon = null;
        if (request.couponCode() != null && !request.couponCode().isBlank() && request.userId() != null) {
            var validationResult = couponService.validateCoupon(
                    request.couponCode(), request.userId(), subtotalCents - promotionDiscount);
            couponDiscount = validationResult.discountCents();
            appliedCoupon = request.couponCode();
        }

        long totalDiscount = promotionDiscount + couponDiscount;
        long totalCents = Math.max(0, subtotalCents - totalDiscount);

        log.info("Cart pricing calculated: subtotal={}, discount={}, total={}", subtotalCents, totalDiscount, totalCents);

        return new PriceCalculationResponse(
                pricedItems,
                subtotalCents,
                totalDiscount,
                totalCents,
                appliedPromotions,
                appliedCoupon);
    }

    private long applyMultiplier(long basePriceCents, BigDecimal multiplier) {
        return BigDecimal.valueOf(basePriceCents)
                .multiply(multiplier)
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
    }
}
