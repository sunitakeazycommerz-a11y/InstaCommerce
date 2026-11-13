package com.instacommerce.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.instacommerce.pricing.domain.Promotion;
import com.instacommerce.pricing.dto.response.PricedItem;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DiscountCalculatorTest {

    private DiscountCalculator discountCalculator;

    @BeforeEach
    void setUp() {
        discountCalculator = new DiscountCalculator();
    }

    @Test
    @DisplayName("Discount precedence: Bundle applied first, percent coupon applied only to post-bundle net subtotal")
    void discountPrecedenceOrder() {
        UUID prodA = UUID.randomUUID();
        UUID prodB = UUID.randomUUID();

        List<PricedItem> items = List.of(
                new PricedItem(prodA, 1000, 2, 2000),
                new PricedItem(prodB, 3000, 1, 3000)
        );

        DiscountCalculator.BundleRule bundleRule = new DiscountCalculator.BundleRule(
                prodA, 2, new BigDecimal("10.0"), null
        );

        DiscountCalculator.CalculationResult result = discountCalculator.calculateDiscounts(
                items,
                List.of(),
                List.of(bundleRule),
                null,
                new BigDecimal("20.0")
        );

        assertThat(result.grossSubtotalCents()).isEqualTo(5000);
        assertThat(result.bundleDiscountCents()).isEqualTo(200);
        assertThat(result.couponDiscountCents()).isEqualTo(960);
        assertThat(result.totalDiscountCents()).isEqualTo(1160);
        assertThat(result.netTotalCents()).isEqualTo(3840);
        assertThat(result.itemDiscountCents()).isZero();
    }

    @Test
    @DisplayName("Ordering invariance: Permuting items array produces identical discount and net total")
    void orderingInvariance() {
        UUID prod1 = UUID.randomUUID();
        UUID prod2 = UUID.randomUUID();
        UUID prod3 = UUID.randomUUID();

        PricedItem item1 = new PricedItem(prod1, 1500, 3, 4500);
        PricedItem item2 = new PricedItem(prod2, 800, 2, 1600);
        PricedItem item3 = new PricedItem(prod3, 2500, 1, 2500);

        List<PricedItem> orderA = List.of(item1, item2, item3);
        List<PricedItem> orderB = List.of(item3, item1, item2);
        List<PricedItem> orderC = List.of(item2, item3, item1);

        DiscountCalculator.BundleRule bundle = new DiscountCalculator.BundleRule(
                prod1, 2, new BigDecimal("15.0"), null
        );

        var resA = discountCalculator.calculateDiscounts(orderA, List.of(), List.of(bundle), null, new BigDecimal("10.0"));
        var resB = discountCalculator.calculateDiscounts(orderB, List.of(), List.of(bundle), null, new BigDecimal("10.0"));
        var resC = discountCalculator.calculateDiscounts(orderC, List.of(), List.of(bundle), null, new BigDecimal("10.0"));

        assertThat(resA.totalDiscountCents()).isEqualTo(resB.totalDiscountCents());
        assertThat(resA.totalDiscountCents()).isEqualTo(resC.totalDiscountCents());
        assertThat(resA.netTotalCents()).isEqualTo(resB.netTotalCents());
        assertThat(resA.netTotalCents()).isEqualTo(resC.netTotalCents());
    }

    @Test
    @DisplayName("Null productId handling: Sorts safely and computes accurate totals without NullPointerException")
    void nullProductIdHandling() {
        UUID prodValid = UUID.randomUUID();
        PricedItem itemNullId1 = new PricedItem(null, 500, 2, 1000);
        PricedItem itemNullId2 = new PricedItem(null, 300, 1, 300);
        PricedItem itemValid = new PricedItem(prodValid, 1200, 1, 1200);

        List<PricedItem> items = List.of(itemNullId1, itemValid, itemNullId2);

        DiscountCalculator.BundleRule productRule = new DiscountCalculator.BundleRule(
                prodValid, 1, new BigDecimal("10.0"), null
        );
        DiscountCalculator.BundleRule cartRule = new DiscountCalculator.BundleRule(
                null, 1, new BigDecimal("5.0"), null
        );

        var result = discountCalculator.calculateDiscounts(
                items,
                List.of(),
                List.of(productRule, cartRule),
                null,
                BigDecimal.ZERO
        );

        assertThat(result.grossSubtotalCents()).isEqualTo(2500);
        assertThat(result.bundleDiscountCents()).isGreaterThan(0);
        assertThat(result.netTotalCents()).isGreaterThan(0);
        assertThat(result.appliedPromotionNames()).contains("BUNDLE_" + prodValid, "BUNDLE_CART");
    }

    @Test
    @DisplayName("HALF_UP rounding precision: Rounds exact half-cents up across lines and coupons")
    void halfUpRoundingPrecision() {
        UUID prod = UUID.randomUUID();

        PricedItem itemA = new PricedItem(prod, 150, 1, 150);
        DiscountCalculator.BundleRule rule15 = new DiscountCalculator.BundleRule(
                prod, 1, new BigDecimal("15.0"), null
        );
        var result15 = discountCalculator.calculateDiscounts(
                List.of(itemA), List.of(), List.of(rule15), null, null
        );
        assertThat(result15.bundleDiscountCents()).isEqualTo(23);
        assertThat(result15.netTotalCents()).isEqualTo(127);

        DiscountCalculator.BundleRule rule11 = new DiscountCalculator.BundleRule(
                prod, 1, new BigDecimal("11.0"), null
        );
        var result11 = discountCalculator.calculateDiscounts(
                List.of(itemA), List.of(), List.of(rule11), null, null
        );
        assertThat(result11.bundleDiscountCents()).isEqualTo(17);
        assertThat(result11.netTotalCents()).isEqualTo(133);

        PricedItem itemCoupon = new PricedItem(prod, 250, 1, 250);
        var resultCoupon = discountCalculator.calculateDiscounts(
                List.of(itemCoupon), List.of(), List.of(), null, new BigDecimal("15.0")
        );
        assertThat(resultCoupon.couponDiscountCents()).isEqualTo(38);
        assertThat(resultCoupon.netTotalCents()).isEqualTo(212);
    }

    @Test
    @DisplayName("Promotion evaluation: Selects highest applicable promotion and enforces min order and max discount limits")
    void orderLevelPromotionEvaluation() {
        UUID prod = UUID.randomUUID();
        List<PricedItem> items = List.of(new PricedItem(prod, 5000, 2, 10000));

        Promotion promoPercentage = new Promotion();
        promoPercentage.setName("SUMMER10");
        promoPercentage.setDiscountType("PERCENTAGE");
        promoPercentage.setDiscountValue(new BigDecimal("10.0"));
        promoPercentage.setMinOrderCents(5000);
        promoPercentage.setMaxDiscountCents(800L);

        Promotion promoFixed = new Promotion();
        promoFixed.setName("FLAT500");
        promoFixed.setDiscountType("FIXED");
        promoFixed.setDiscountValue(new BigDecimal("500"));
        promoFixed.setMinOrderCents(2000);

        var result = discountCalculator.calculateDiscounts(
                items,
                List.of(promoPercentage, promoFixed),
                List.of(),
                null,
                null
        );

        assertThat(result.bundleDiscountCents()).isEqualTo(800);
        assertThat(result.appliedPromotionNames()).containsExactly("SUMMER10");
        assertThat(result.netTotalCents()).isEqualTo(9200);
    }

    @Test
    @DisplayName("Empty and null inputs: Gracefully returns zero calculation result")
    void emptyAndNullInputs() {
        var nullResult = discountCalculator.calculateDiscounts(null, null, null, null, null);
        assertThat(nullResult.grossSubtotalCents()).isZero();
        assertThat(nullResult.totalDiscountCents()).isZero();
        assertThat(nullResult.netTotalCents()).isZero();
        assertThat(nullResult.appliedPromotionNames()).isEmpty();

        var emptyResult = discountCalculator.calculateDiscounts(List.of(), null, null, null, null);
        assertThat(emptyResult.grossSubtotalCents()).isZero();
        assertThat(emptyResult.totalDiscountCents()).isZero();
        assertThat(emptyResult.netTotalCents()).isZero();
        assertThat(emptyResult.appliedPromotionNames()).isEmpty();
    }

    @Test
    @DisplayName("Property-based test: Over 200 random carts, discount never exceeds gross subtotal and net >= 0")
    void propertyBasedDiscountBoundaries() {
        Random random = new Random(42);

        for (int i = 0; i < 200; i++) {
            int itemCount = 1 + random.nextInt(10);
            List<PricedItem> items = new ArrayList<>();
            for (int j = 0; j < itemCount; j++) {
                UUID pid = random.nextBoolean() ? UUID.randomUUID() : null;
                long unitPrice = 50 + random.nextInt(5000);
                int qty = 1 + random.nextInt(5);
                items.add(new PricedItem(pid, unitPrice, qty, unitPrice * qty));
            }

            List<DiscountCalculator.BundleRule> bundles = new ArrayList<>();
            if (random.nextBoolean()) {
                bundles.add(new DiscountCalculator.BundleRule(
                        items.get(0).productId(), 1, BigDecimal.valueOf(random.nextInt(50)), null
                ));
            }

            BigDecimal couponPct = BigDecimal.valueOf(random.nextInt(100));

            var result = discountCalculator.calculateDiscounts(items, List.of(), bundles, null, couponPct);

            assertThat(result.totalDiscountCents())
                    .as("Total discount must never exceed gross subtotal")
                    .isLessThanOrEqualTo(result.grossSubtotalCents());

            assertThat(result.netTotalCents())
                    .as("Net total must be non-negative")
                    .isGreaterThanOrEqualTo(0);

            assertThat(result.grossSubtotalCents())
                    .isEqualTo(result.netTotalCents() + result.totalDiscountCents());
        }
    }
}
