package com.instacommerce.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;

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

        // 2 items: Prod A (qty 2 @ 1000 = 2000), Prod B (qty 1 @ 3000 = 3000) -> Gross = 5000 cents ($50.00)
        List<PricedItem> items = List.of(
                new PricedItem(prodA, 1000, 2, 2000),
                new PricedItem(prodB, 3000, 1, 3000)
        );

        // Bundle: Buy 2 of Prod A get 10% off Prod A line -> 10% of 2000 = 200 cents
        DiscountCalculator.BundleRule bundleRule = new DiscountCalculator.BundleRule(
                prodA, 2, new BigDecimal("10.0"), null
        );

        // Post-bundle net subtotal = 5000 - 200 = 4800 cents.
        // Global coupon: 20% off applied ONLY to post-bundle net (20% of 4800 = 960 cents)
        // NOT 20% of gross 5000 (which would have been 1000 cents)!
        DiscountCalculator.CalculationResult result = discountCalculator.calculateDiscounts(
                items,
                List.of(),
                List.of(bundleRule),
                null,
                new BigDecimal("20.0")
        );

        assertThat(result.grossSubtotalCents()).isEqualTo(5000);
        assertThat(result.bundleDiscountCents()).isEqualTo(200);
        assertThat(result.couponDiscountCents()).isEqualTo(960); // 20% of 4800
        assertThat(result.totalDiscountCents()).isEqualTo(1160);
        assertThat(result.netTotalCents()).isEqualTo(3840);
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
    @DisplayName("Property-based test: Over 200 random carts, discount never exceeds gross subtotal and net >= 0")
    void propertyBasedDiscountBoundaries() {
        Random random = new Random(42);

        for (int i = 0; i < 200; i++) {
            int itemCount = 1 + random.nextInt(10);
            List<PricedItem> items = new ArrayList<>();
            for (int j = 0; j < itemCount; j++) {
                UUID pid = UUID.randomUUID();
                long unitPrice = 50 + random.nextInt(5000); // 50c to $50
                int qty = 1 + random.nextInt(5);
                items.add(new PricedItem(pid, unitPrice, qty, unitPrice * qty));
            }

            // Random bundle discount (0 to 50%)
            List<DiscountCalculator.BundleRule> bundles = new ArrayList<>();
            if (random.nextBoolean()) {
                bundles.add(new DiscountCalculator.BundleRule(
                        items.get(0).productId(), 1, BigDecimal.valueOf(random.nextInt(50)), null
                ));
            }

            // Random coupon discount (0 to 100%)
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
