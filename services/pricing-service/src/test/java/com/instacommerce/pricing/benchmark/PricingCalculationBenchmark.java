package com.instacommerce.pricing.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.instacommerce.pricing.dto.response.PricedItem;
import com.instacommerce.pricing.service.DiscountCalculator;
import com.instacommerce.pricing.service.TaxCalculationService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.*;

@Tag("benchmark")
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class PricingCalculationBenchmark {

    private TaxCalculationService taxCalculationService;
    private DiscountCalculator discountCalculator;
    private List<TaxCalculationService.TaxableLineItem> taxItems;
    private List<PricedItem> discountItems;
    private List<DiscountCalculator.BundleRule> bundleRules;
    private BigDecimal defaultTaxRate;

    @Setup(Level.Trial)
    @BeforeEach
    public void setup() {
        taxCalculationService = new TaxCalculationService();
        discountCalculator = new DiscountCalculator();
        defaultTaxRate = new BigDecimal("0.0825");

        taxItems = new ArrayList<>();
        discountItems = new ArrayList<>();
        bundleRules = new ArrayList<>();

        for (int i = 0; i < 50; i++) {
            UUID productId = UUID.randomUUID();
            long priceCents = 299 + (i * 50);
            int quantity = (i % 3) + 1;

            taxItems.add(new TaxCalculationService.TaxableLineItem(
                    productId,
                    "Item-" + i,
                    priceCents * quantity,
                    (i % 5 == 0) ? new BigDecimal("0.05") : defaultTaxRate
            ));

            discountItems.add(new PricedItem(
                    productId,
                    priceCents,
                    quantity,
                    priceCents * quantity
            ));

            if (i % 15 == 0) {
                bundleRules.add(new DiscountCalculator.BundleRule(
                        productId,
                        2,
                        new BigDecimal("15.0"),
                        500L
                ));
            }
        }
    }

    @Benchmark
    public TaxCalculationService.TaxCalculationResult benchmarkApplyTax() {
        return taxCalculationService.calculateTaxes(taxItems, defaultTaxRate);
    }

    @Benchmark
    public DiscountCalculator.CalculationResult benchmarkCalculateCartTotal() {
        return discountCalculator.calculateDiscounts(
                discountItems,
                List.of(),
                bundleRules,
                null,
                new BigDecimal("10.0")
        );
    }

    @Test
    @DisplayName("JMH Benchmark Assertion: Tax and Cart calculation throughput exceeds 50,000 ops/sec")
    void testThroughputExceedsFiftyThousandOpsPerSec() {
        setup();

        for (int i = 0; i < 20_000; i++) {
            benchmarkApplyTax();
            benchmarkCalculateCartTotal();
        }

        int iterations = 100_000;
        long totalTaxGross = 0;
        long startTax = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            TaxCalculationService.TaxCalculationResult res = benchmarkApplyTax();
            totalTaxGross += res.totalGrossCents();
        }
        long durationTaxNs = System.nanoTime() - startTax;
        double taxOpsPerSec = ((double) iterations / durationTaxNs) * 1_000_000_000.0;
        assertThat(totalTaxGross).isGreaterThan(0);

        long totalCartNet = 0;
        long startCart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            DiscountCalculator.CalculationResult res = benchmarkCalculateCartTotal();
            totalCartNet += res.netTotalCents();
        }
        long durationCartNs = System.nanoTime() - startCart;
        double cartOpsPerSec = ((double) iterations / durationCartNs) * 1_000_000_000.0;
        assertThat(totalCartNet).isGreaterThan(0);

        System.out.printf("Throughput Results (50-item basket):%n" +
                        "  applyTax:           %,.0f ops/sec%n" +
                        "  calculateCartTotal: %,.0f ops/sec%n",
                taxOpsPerSec, cartOpsPerSec);

        assertThat(taxOpsPerSec)
                .as("applyTax throughput must exceed 50,000 ops/sec")
                .isGreaterThan(50_000.0);

        assertThat(cartOpsPerSec)
                .as("calculateCartTotal throughput must exceed 50,000 ops/sec")
                .isGreaterThan(50_000.0);
    }
}
