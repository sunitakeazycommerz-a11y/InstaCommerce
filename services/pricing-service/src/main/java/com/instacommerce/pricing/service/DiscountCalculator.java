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
 * Deterministic discount calculator enforcing strict precedence:
 *   1. Item SKU discounts (incorporated in unit prices)
 *   2. Category bundle / volume promotions
 *   3. Global promo / coupon codes applied against post-bundle net subtotal
 */
@Component
public class DiscountCalculator {

    private static final Logger log = LoggerFactory.getLogger(DiscountCalculator.class);

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private static final String PROMO_BUNDLE_CART = "BUNDLE_CART";

    private static final Comparator<PricedItem> ITEM_COMPARATOR =
            Comparator.comparing(PricedItem::productId, Comparator.nullsLast(Comparator.naturalOrder()));

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

        List<PricedItem> deterministicItems = new ArrayList<>(items);
        if (deterministicItems.size() > 1) {
            deterministicItems.sort(ITEM_COMPARATOR);
        }

        long grossSubtotal = 0;
        for (PricedItem item : deterministicItems) {
            grossSubtotal += item.lineTotalCents();
        }

        List<String> appliedPromotions = new ArrayList<>();
        long bundleDiscounts = 0;

        if (bundleRules != null && !bundleRules.isEmpty()) {
            for (BundleRule rule : bundleRules) {
                UUID targetId = rule.targetProductId();
                if (targetId == null) {
                    for (PricedItem item : deterministicItems) {
                        bundleDiscounts += applyBundleRule(rule, item, appliedPromotions);
                    }
                } else {
                    int firstIdx = findFirstByProductId(deterministicItems, targetId);
                    if (firstIdx >= 0) {
                        for (int i = firstIdx; i < deterministicItems.size(); i++) {
                            PricedItem item = deterministicItems.get(i);
                            if (!targetId.equals(item.productId())) {
                                break;
                            }
                            bundleDiscounts += applyBundleRule(rule, item, appliedPromotions);
                        }
                    }
                }
            }
        }

        if (promotions != null && !promotions.isEmpty()) {
            long remainingAfterBundles = Math.max(0, grossSubtotal - bundleDiscounts);
            Promotion bestPromo = null;
            long promoDiscount = 0;

            for (Promotion promo : promotions) {
                if (remainingAfterBundles < promo.getMinOrderCents()) {
                    continue;
                }
                long discount;
                if ("PERCENTAGE".equalsIgnoreCase(promo.getDiscountType())) {
                    discount = calculatePercentDiscount(remainingAfterBundles, promo.getDiscountValue());
                } else {
                    discount = promo.getDiscountValue() != null ? promo.getDiscountValue().longValue() : 0;
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

        bundleDiscounts = Math.min(bundleDiscounts, grossSubtotal);

        long netSubtotalPostBundle = Math.max(0, grossSubtotal - bundleDiscounts);
        long couponDiscount = 0;

        if (couponDiscountPercentage != null && couponDiscountPercentage.compareTo(BigDecimal.ZERO) > 0) {
            couponDiscount = calculatePercentDiscount(netSubtotalPostBundle, couponDiscountPercentage);
        } else if (couponValidationDiscount != null && couponValidationDiscount > 0) {
            couponDiscount = couponValidationDiscount;
        }

        couponDiscount = Math.min(couponDiscount, netSubtotalPostBundle);

        long totalDiscount = Math.min(bundleDiscounts + couponDiscount, grossSubtotal);
        long netTotal = Math.max(0, grossSubtotal - totalDiscount);

        log.debug("Discount calculation: gross={}, item=0, bundle={}, coupon={}, total={}, net={}",
                grossSubtotal, bundleDiscounts, couponDiscount, totalDiscount, netTotal);

        return new CalculationResult(
                grossSubtotal,
                0,
                bundleDiscounts,
                couponDiscount,
                totalDiscount,
                netTotal,
                appliedPromotions
        );
    }

    private static int findFirstByProductId(List<PricedItem> items, UUID targetProductId) {
        int low = 0;
        int high = items.size() - 1;
        int result = -1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            UUID midId = items.get(mid).productId();
            int cmp;
            if (midId == null) {
                cmp = (targetProductId == null) ? 0 : 1;
            } else if (targetProductId == null) {
                cmp = -1;
            } else {
                cmp = midId.compareTo(targetProductId);
            }

            if (cmp >= 0) {
                if (cmp == 0) {
                    result = mid;
                }
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }

        return result;
    }

    private static long applyBundleRule(BundleRule rule, PricedItem item, List<String> appliedPromotions) {
        if (item.quantity() >= rule.minQuantity()) {
            long discount = calculatePercentDiscount(item.lineTotalCents(), rule.discountPercent());
            if (rule.maxDiscountCents() != null) {
                discount = Math.min(discount, rule.maxDiscountCents());
            }
            appliedPromotions.add(rule.targetProductId() != null ? "BUNDLE_" + rule.targetProductId() : PROMO_BUNDLE_CART);
            return discount;
        }
        return 0;
    }

    private static long calculatePercentDiscount(long baseCents, BigDecimal percent) {
        if (baseCents <= 0 || percent == null || percent.signum() <= 0) {
            return 0;
        }
        return BigDecimal.valueOf(baseCents)
                .multiply(percent)
                .divide(ONE_HUNDRED, 0, RoundingMode.HALF_UP)
                .longValue();
    }
}
