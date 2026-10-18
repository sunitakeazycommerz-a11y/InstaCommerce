package com.instacommerce.pricing.service;

import com.instacommerce.pricing.domain.Promotion;
import com.instacommerce.pricing.dto.response.PricedItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * High-precision, deterministic discount calculator enforcing strict precedence:
 *   1. Item SKU discounts (e.g., markdowns, base price rule multipliers)
 *   2. Category bundle / volume promotions (e.g., Buy 2 Get 10% Off)
 *   3. Global promo / coupon codes applied strictly against net subtotal post-bundle deductions
 *
 * Guarantees:
 *   - Total discount never exceeds gross subtotal (net payable >= 0).
 *   - Evaluation order is deterministic and independent of item array ordering in requests.
 *   - Percent-off coupons compound exclusively against post-bundle net subtotal.
 */
@Component
public class DiscountCalculator {

    private static final Logger log = LoggerFactory.getLogger(DiscountCalculator.class);

    public record BundleRule(
            UUID targetProductId,
            int minQuantity,
            BigDecimal discountPercent,
            Long maxDiscountCents
    ) {}

    public record CalculationResult(
            long grossSubtotalCents,
            long itemDiscountCents,
            long bundleDiscountCents,
            long couponDiscountCents,
            long totalDiscountCents,
            long netTotalCents,
            List<String> appliedPromotionNames
    ) {}

    /**
     * Calculates combined discounts enforcing strict precedence:
     * 1) Item SKU discount -> 2) Bundle discount -> 3) Global coupon code on net subtotal.
     *
     * @param items List of priced items in the cart
     * @param promotions List of active candidate promotions
     * @param bundleRules List of applicable bundle/volume discount rules
     * @param couponValidationDiscount Raw coupon discount or null
     * @param couponDiscountPercentage Optional percent-off coupon rate (e.g., 20.0 for 20% off)
     * @return CalculationResult with exact discount breakdown
     */
    public CalculationResult calculateDiscounts(
            List<PricedItem> items,
            List<Promotion> promotions,
            List<BundleRule> bundleRules,
            Long couponValidationDiscount,
            BigDecimal couponDiscountPercentage
    ) {
        if (items == null || items.isEmpty()) {
            return new CalculationResult(0, 0, 0, 0, 0, 0, List.of());
        }

        // 0. Sort items deterministically by productId to eliminate array ordering variations
        List<PricedItem> deterministicItems = new ArrayList<>(items);
        deterministicItems.sort(Comparator.comparing(item -> item.productId().toString()));

        long grossSubtotal = 0;
        for (PricedItem item : deterministicItems) {
            grossSubtotal += item.lineTotalCents();
        }

        List<String> appliedPromotions = new ArrayList<>();

        // Level 1: Item SKU discounts (already incorporated in unit prices or item-level specials)
        long itemDiscounts = 0;

        // Level 2: Category bundle discounts
        long bundleDiscounts = 0;
        if (bundleRules != null && !bundleRules.isEmpty()) {
            for (BundleRule rule : bundleRules) {
                for (PricedItem item : deterministicItems) {
                    if (rule.targetProductId() == null || rule.targetProductId().equals(item.productId())) {
                        if (item.quantity() >= rule.minQuantity()) {
                            long lineBase = item.lineTotalCents();
                            long discount = BigDecimal.valueOf(lineBase)
                                    .multiply(rule.discountPercent())
                                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                                    .longValue();
                            if (rule.maxDiscountCents() != null) {
                                discount = Math.min(discount, rule.maxDiscountCents());
                            }
                            bundleDiscounts += discount;
                            appliedPromotions.add("BUNDLE_" + (rule.targetProductId() != null ? rule.targetProductId() : "CART"));
                        }
                    }
                }
            }
        }

        // Evaluate order-level promotions if provided
        long promoDiscount = 0;
        if (promotions != null && !promotions.isEmpty()) {
            long remainingAfterBundles = Math.max(0, grossSubtotal - itemDiscounts - bundleDiscounts);
            Promotion bestPromo = null;
            for (Promotion promo : promotions) {
                if (remainingAfterBundles < promo.getMinOrderCents()) {
                    continue;
                }
                long discount;
                if ("PERCENTAGE".equalsIgnoreCase(promo.getDiscountType())) {
                    discount = BigDecimal.valueOf(remainingAfterBundles)
                            .multiply(promo.getDiscountValue())
                            .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                            .longValue();
                } else {
                    discount = promo.getDiscountValue().longValue();
                }
                if (promo.getMaxDiscountCents() != null) {
                    discount = Math.min(discount, promo.getMaxDiscountCents());
                }
                discount = Math.min(discount, remainingAfterBundles);
                if (discount > promoDiscount) {
                    promoDiscount = discount;
                    bestPromo = promo;
                }
            }
            if (bestPromo != null) {
                appliedPromotions.add(bestPromo.getName());
                bundleDiscounts += promoDiscount;
            }
        }

        bundleDiscounts = Math.min(bundleDiscounts, Math.max(0, grossSubtotal - itemDiscounts));

        // Level 3: Global coupon code applied STRICTLY to net subtotal post-bundle deductions
        long netSubtotalPostBundle = Math.max(0, grossSubtotal - itemDiscounts - bundleDiscounts);
        long couponDiscount = 0;

        if (couponDiscountPercentage != null && couponDiscountPercentage.compareTo(BigDecimal.ZERO) > 0) {
            couponDiscount = BigDecimal.valueOf(netSubtotalPostBundle)
                    .multiply(couponDiscountPercentage)
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                    .longValue();
        } else if (couponValidationDiscount != null && couponValidationDiscount > 0) {
            couponDiscount = couponValidationDiscount;
        }

        // Coupon cannot exceed remaining net subtotal
        couponDiscount = Math.min(couponDiscount, netSubtotalPostBundle);

        long totalDiscount = itemDiscounts + bundleDiscounts + couponDiscount;
        // Strict boundary: total discount must never exceed gross subtotal
        totalDiscount = Math.min(totalDiscount, grossSubtotal);
        long netTotal = Math.max(0, grossSubtotal - totalDiscount);

        log.debug("Discount calculation: gross={}, item={}, bundle={}, coupon={}, total={}, net={}",
                grossSubtotal, itemDiscounts, bundleDiscounts, couponDiscount, totalDiscount, netTotal);

        return new CalculationResult(
                grossSubtotal,
                itemDiscounts,
                bundleDiscounts,
                couponDiscount,
                totalDiscount,
                netTotal,
                appliedPromotions
        );
    }
}
