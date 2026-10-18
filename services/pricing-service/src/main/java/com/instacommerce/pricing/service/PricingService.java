package com.instacommerce.pricing.service;

import com.instacommerce.pricing.domain.PriceRule;
import com.instacommerce.pricing.domain.Promotion;
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
    private final QuoteTokenService quoteTokenService;
    private final DiscountCalculator discountCalculator;

    public PricingService(PriceRuleRepository priceRuleRepository,
                          PromotionService promotionService,
                          CouponService couponService,
                          QuoteTokenService quoteTokenService,
                          DiscountCalculator discountCalculator) {
        this.priceRuleRepository = priceRuleRepository;
        this.promotionService = promotionService;
        this.couponService = couponService;
        this.quoteTokenService = quoteTokenService;
        this.discountCalculator = discountCalculator;
    }

    public PricingService(PriceRuleRepository priceRuleRepository,
                          PromotionService promotionService,
                          CouponService couponService,
                          QuoteTokenService quoteTokenService) {
        this(priceRuleRepository, promotionService, couponService, quoteTokenService, new DiscountCalculator());
    }

    @Cacheable(value = "productPrices", key = "#productId")
    @Transactional(readOnly = true)
    public long calculatePrice(UUID productId) {
        Instant now = Instant.now();
        PriceRule rule = priceRuleRepository.findActiveRuleByProductId(productId, now)
                .orElseThrow(() -> new PriceRuleNotFoundException(productId.toString()));
        return applyMultiplier(rule.getBasePriceCents(), rule.getMultiplier());
    }

    @Transactional
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

        // Evaluate candidate promotions
        var activePromotions = promotionService.findActivePromotions();
        long preliminaryPromoDiscount = 0;
        for (Promotion promotion : activePromotions) {
            if (subtotalCents >= promotion.getMinOrderCents()
                    && !now.isBefore(promotion.getStartAt())
                    && now.isBefore(promotion.getEndAt())) {
                long discount = promotionService.calculateDiscount(promotion, subtotalCents);
                if (discount > preliminaryPromoDiscount) {
                    preliminaryPromoDiscount = discount;
                }
            }
        }

        // Apply coupon if provided, evaluated strictly on net subtotal post-promotion
        Long couponValidationDiscount = null;
        String appliedCoupon = null;
        if (request.couponCode() != null && !request.couponCode().isBlank() && request.userId() != null) {
            long netBase = Math.max(0, subtotalCents - preliminaryPromoDiscount);
            var validationResult = couponService.validateCoupon(
                    request.couponCode(), request.userId(), netBase);
            couponValidationDiscount = validationResult.discountCents();
            appliedCoupon = request.couponCode();
        }

        // Strict precedence discount calculation via DiscountCalculator
        var calcResult = discountCalculator.calculateDiscounts(
                pricedItems,
                activePromotions,
                List.of(),
                couponValidationDiscount,
                null
        );

        long totalDiscount = calcResult.totalDiscountCents();
        long totalCents = calcResult.netTotalCents();
        List<String> appliedPromotions = calcResult.appliedPromotionNames();

        log.info("Cart pricing calculated: subtotal={}, discount={}, total={}", subtotalCents, totalDiscount, totalCents);

        PriceCalculationResponse intermediateResponse = new PriceCalculationResponse(
                pricedItems,
                subtotalCents,
                totalDiscount,
                totalCents,
                appliedPromotions,
                appliedCoupon,
                null,
                null);

        QuoteTokenService.QuoteResult quoteResult = quoteTokenService.issueQuote(
                request.userId(), intermediateResponse, appliedCoupon);

        return new PriceCalculationResponse(
                pricedItems,
                subtotalCents,
                totalDiscount,
                totalCents,
                appliedPromotions,
                appliedCoupon,
                quoteResult.quoteId(),
                quoteResult.quoteToken());
    }

    private long applyMultiplier(long basePriceCents, BigDecimal multiplier) {
        return BigDecimal.valueOf(basePriceCents)
                .multiply(multiplier)
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
    }
}
