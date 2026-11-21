package com.instacommerce.pricing.service;

import com.instacommerce.pricing.domain.PriceRule;
import com.instacommerce.pricing.domain.Promotion;
import com.instacommerce.pricing.dto.request.CartItem;
import com.instacommerce.pricing.dto.request.PriceCalculationRequest;
import com.instacommerce.pricing.dto.request.TaxCalculationRequest;
import com.instacommerce.pricing.dto.response.BatchPriceResponse;
import com.instacommerce.pricing.dto.response.PriceCalculationResponse;
import com.instacommerce.pricing.dto.response.PricedItem;
import com.instacommerce.pricing.dto.response.TaxCalculationResponse;
import com.instacommerce.pricing.exception.PriceRuleNotFoundException;
import com.instacommerce.pricing.repository.PriceRuleRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final TaxCalculationService taxCalculationService;
    private final SurgePricingService surgePricingService;

    @Autowired
    public PricingService(PriceRuleRepository priceRuleRepository,
                          PromotionService promotionService,
                          CouponService couponService,
                          QuoteTokenService quoteTokenService,
                          DiscountCalculator discountCalculator,
                          TaxCalculationService taxCalculationService,
                          SurgePricingService surgePricingService) {
        this.priceRuleRepository = priceRuleRepository;
        this.promotionService = promotionService;
        this.couponService = couponService;
        this.quoteTokenService = quoteTokenService;
        this.discountCalculator = discountCalculator != null ? discountCalculator : new DiscountCalculator();
        this.taxCalculationService = taxCalculationService != null ? taxCalculationService : new TaxCalculationService();
        this.surgePricingService = surgePricingService;
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
    public BatchPriceResponse calculateBatchPrices(Collection<UUID> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return new BatchPriceResponse(List.of(), List.of());
        }
        List<UUID> uniqueIds = new ArrayList<>(new LinkedHashSet<>(productIds.stream()
                .filter(Objects::nonNull)
                .toList()));
        if (uniqueIds.isEmpty()) {
            return new BatchPriceResponse(List.of(), List.of());
        }
        Instant now = Instant.now();
        Map<UUID, PriceRule> rulesByProduct = new LinkedHashMap<>();
        if (uniqueIds.size() == 1) {
            UUID singleId = uniqueIds.getFirst();
            priceRuleRepository.findActiveRuleByProductId(singleId, now)
                    .ifPresent(rule -> rulesByProduct.put(singleId, rule));
        } else {
            for (PriceRule rule : priceRuleRepository.findActiveRulesByProductIds(uniqueIds, now)) {
                rulesByProduct.putIfAbsent(rule.getProductId(), rule);
            }
        }
        List<PricedItem> items = new ArrayList<>(rulesByProduct.size());
        List<UUID> unavailableProductIds = new ArrayList<>();
        for (UUID productId : uniqueIds) {
            PriceRule rule = rulesByProduct.get(productId);
            if (rule == null) {
                unavailableProductIds.add(productId);
                continue;
            }
            long unitPrice = applyMultiplier(rule.getBasePriceCents(), rule.getMultiplier());
            items.add(new PricedItem(productId, unitPrice, 1, unitPrice, "INR"));
        }
        return new BatchPriceResponse(items, unavailableProductIds);
    }

    @Transactional
    public PriceCalculationResponse calculateCartPrice(PriceCalculationRequest request) {
        Instant now = Instant.now();
        List<UUID> productIds = request.items().stream().map(CartItem::productId).distinct().toList();
        Map<UUID, PriceRule> rulesByProduct = loadActiveRules(productIds, now);

        List<PricedItem> pricedItems = new ArrayList<>(request.items().size());
        long subtotalCents = 0;

        for (CartItem item : request.items()) {
            PriceRule rule = rulesByProduct.get(item.productId());
            if (rule == null) {
                rule = priceRuleRepository.findActiveRuleByProductId(item.productId(), now)
                        .orElseThrow(() -> new PriceRuleNotFoundException(item.productId().toString()));
            }

            long unitPrice = applyMultiplier(rule.getBasePriceCents(), rule.getMultiplier());
            long lineTotal = Math.multiplyExact(unitPrice, (long) item.quantity());
            subtotalCents = Math.addExact(subtotalCents, lineTotal);

            pricedItems.add(new PricedItem(item.productId(), unitPrice, item.quantity(), lineTotal));
        }

        List<Promotion> candidatePromotions = promotionService.findActivePromotions();
        List<UUID> promoIds = candidatePromotions.stream()
                .map(Promotion::getId)
                .filter(Objects::nonNull)
                .toList();
        Map<UUID, Long> userPromoRedemptions = promotionService.findUserRedemptionCounts(request.userId(), promoIds);

        List<Promotion> activePromotions = candidatePromotions.stream()
                .filter(p -> PromotionService.isEligibleAt(p, now,
                        p.getId() != null ? userPromoRedemptions.getOrDefault(p.getId(), 0L) : 0L))
                .toList();

        long preliminaryStackablePromoDiscount = 0;
        for (Promotion promotion : activePromotions) {
            if (!promotion.isStackableWithCoupon()) {
                continue;
            }
            if (subtotalCents >= promotion.getMinOrderCents()) {
                long discount = promotionService.calculateDiscount(promotion, subtotalCents);
                if (discount > preliminaryStackablePromoDiscount) {
                    preliminaryStackablePromoDiscount = discount;
                }
            }
        }

        Long couponValidationDiscount = null;
        String validatedCouponCode = null;
        if (request.couponCode() != null && !request.couponCode().isBlank() && request.userId() != null) {
            long netBase = Math.max(0, subtotalCents - preliminaryStackablePromoDiscount);
            var validationResult = couponService.validateCoupon(
                    request.couponCode(), request.userId(), netBase);
            couponValidationDiscount = validationResult.discountCents();
            validatedCouponCode = validationResult.couponCode();
        }

        var calcResult = discountCalculator.calculateDiscounts(
                pricedItems,
                activePromotions,
                List.of(),
                couponValidationDiscount,
                null
        );

        long deliveryFeeCents = 0L;
        if (request.storeId() != null && surgePricingService != null) {
            var surge = surgePricingService.evaluateSurge(request.storeId());
            if (surge != null && surge.isSurgeActive()) {
                deliveryFeeCents = surge.surgeDeliveryFeeCents();
            }
        }

        long totalDiscount = calcResult.totalDiscountCents();
        long couponDiscountCents = calcResult.couponDiscountCents();
        long netGoodsTotal = calcResult.netTotalCents();
        long totalCents = Math.addExact(netGoodsTotal, deliveryFeeCents);
        String appliedCoupon = couponDiscountCents > 0 ? validatedCouponCode : null;
        List<String> appliedPromotions = calcResult.appliedPromotionNames();
        long estimatedTaxCents = taxCalculationService.calculateTaxForSubtotal(
                netGoodsTotal, TaxCalculationService.DEFAULT_TAX_RATE);
        BigDecimal taxRate = TaxCalculationService.DEFAULT_TAX_RATE;

        log.info("Cart pricing calculated: subtotal={}, discount={}, couponDiscount={}, delivery={}, total={}, estimatedTax={}",
                subtotalCents, totalDiscount, couponDiscountCents, deliveryFeeCents, totalCents, estimatedTaxCents);

        PriceCalculationResponse intermediateResponse = new PriceCalculationResponse(
                pricedItems,
                subtotalCents,
                totalDiscount,
                deliveryFeeCents,
                totalCents,
                "INR",
                appliedPromotions,
                appliedCoupon,
                null,
                null,
                estimatedTaxCents,
                taxRate,
                couponDiscountCents);

        QuoteTokenService.QuoteResult quoteResult = quoteTokenService.issueQuote(
                request.userId(), intermediateResponse, appliedCoupon);

        return new PriceCalculationResponse(
                pricedItems,
                subtotalCents,
                totalDiscount,
                deliveryFeeCents,
                totalCents,
                "INR",
                appliedPromotions,
                appliedCoupon,
                quoteResult.quoteId(),
                quoteResult.quoteToken(),
                estimatedTaxCents,
                taxRate,
                couponDiscountCents);
    }

    private Map<UUID, PriceRule> loadActiveRules(Collection<UUID> productIds, Instant now) {
        Map<UUID, PriceRule> rules = new LinkedHashMap<>();
        if (productIds.size() > 1) {
            for (PriceRule rule : priceRuleRepository.findActiveRulesByProductIds(productIds, now)) {
                rules.putIfAbsent(rule.getProductId(), rule);
            }
        }
        return rules;
    }

    public TaxCalculationResponse calculateTax(TaxCalculationRequest request) {
        List<TaxCalculationService.TaxableLineItem> lineItems = request.items().stream()
                .map(item -> new TaxCalculationService.TaxableLineItem(
                        item.itemId(),
                        item.itemName(),
                        item.netAmountCents(),
                        item.taxRate()))
                .toList();
        TaxCalculationService.TaxCalculationResult result = taxCalculationService.calculateTaxes(
                lineItems, request.fallbackTaxRate());
        List<TaxCalculationResponse.LineItemTaxResponse> responseItems = result.lineItems().stream()
                .map(line -> new TaxCalculationResponse.LineItemTaxResponse(
                        line.itemId(),
                        line.itemName(),
                        line.netAmountCents(),
                        line.taxCents(),
                        line.grossAmountCents(),
                        line.effectiveTaxRate()))
                .toList();
        return new TaxCalculationResponse(
                responseItems,
                result.totalNetCents(),
                result.totalTaxCents(),
                result.totalGrossCents(),
                result.fractionalRemainderCents());
    }

    private long applyMultiplier(long basePriceCents, BigDecimal multiplier) {
        return BigDecimal.valueOf(basePriceCents)
                .multiply(multiplier)
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
    }
}
