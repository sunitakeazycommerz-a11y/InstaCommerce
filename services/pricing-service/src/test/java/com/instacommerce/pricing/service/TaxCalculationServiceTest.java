package com.instacommerce.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TaxCalculationServiceTest {

    private TaxCalculationService taxService;

    @BeforeEach
    void setUp() {
        taxService = new TaxCalculationService();
    }

    @Test
    @DisplayName("Banker's rounding with remainder carry-over eliminates 1-cent discrepancy with invoice total")
    void taxCalculationEliminatesDiscrepancy() {
        // 3 items with 33 cents each, tax rate 10%
        // Without carry-over: 33 * 0.10 = 3.3 -> 3 cents each -> total item tax = 9 cents.
        // Total invoice: 99 * 0.10 = 9.9 -> 10 cents. Discrepancy = 1 cent!
        // With remainder carry-over:
        // Item 1: 3.3 -> 3c (carry +0.3)
        // Item 2: 3.3 + 0.3 = 3.6 -> 4c (carry -0.4)
        // Item 3: 3.3 - 0.4 = 2.9 -> 3c (carry -0.1)
        // Sum of item taxes = 3 + 4 + 3 = 10 cents == Invoice total tax (10 cents)!
        List<TaxCalculationService.TaxableLineItem> items = List.of(
                new TaxCalculationService.TaxableLineItem(UUID.randomUUID(), "Item 1", 33, new BigDecimal("0.10")),
                new TaxCalculationService.TaxableLineItem(UUID.randomUUID(), "Item 2", 33, new BigDecimal("0.10")),
                new TaxCalculationService.TaxableLineItem(UUID.randomUUID(), "Item 3", 33, new BigDecimal("0.10"))
        );

        TaxCalculationService.TaxCalculationResult result = taxService.calculateTaxes(items, new BigDecimal("0.10"));

        long sumOfLineTaxes = result.lineItems().stream()
                .mapToLong(TaxCalculationService.LineItemTax::taxCents)
                .sum();

        long invoiceLevelTax = taxService.calculateTaxForSubtotal(result.totalNetCents(), new BigDecimal("0.10"));

        assertThat(sumOfLineTaxes)
                .as("Sum of line item taxes must exactly match total tax cents")
                .isEqualTo(result.totalTaxCents());

        assertThat(result.totalTaxCents())
                .as("Total tax cents must equal invoice-level calculated tax (eliminating 1-cent drift)")
                .isEqualTo(invoiceLevelTax)
                .isEqualTo(10L);
    }

    @Test
    @DisplayName("Property-based test: For 500 diverse cart compositions, sum of item taxes ALWAYS equals total tax")
    void propertyBasedTaxEquivalence() {
        Random random = new Random(1337);

        for (int i = 0; i < 500; i++) {
            int itemCount = 1 + random.nextInt(15);
            List<TaxCalculationService.TaxableLineItem> items = new ArrayList<>();
            BigDecimal uniformRate = new BigDecimal("0.18"); // 18% standard GST

            for (int j = 0; j < itemCount; j++) {
                long net = 1 + random.nextInt(9999); // 1 cent to $99.99
                items.add(new TaxCalculationService.TaxableLineItem(
                        UUID.randomUUID(), "Product-" + j, net, uniformRate
                ));
            }

            var result = taxService.calculateTaxes(items, uniformRate);

            long lineTaxSum = result.lineItems().stream()
                    .mapToLong(TaxCalculationService.LineItemTax::taxCents)
                    .sum();

            assertThat(lineTaxSum)
                    .as("Sum of individual line taxes must equal totalTaxCents")
                    .isEqualTo(result.totalTaxCents());

            assertThat(result.totalGrossCents())
                    .as("Total gross must equal total net + total tax")
                    .isEqualTo(result.totalNetCents() + result.totalTaxCents());
        }
    }
}
