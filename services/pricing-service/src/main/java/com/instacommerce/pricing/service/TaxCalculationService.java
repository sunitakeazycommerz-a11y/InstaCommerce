package com.instacommerce.pricing.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Standardized financial tax calculation engine implementing Banker's rounding (HALF_EVEN)
 * with continuous fractional cent remainder carry-over across line items.
 *
 * Prevents 1-cent rounding discrepancies between itemized cart line taxes and invoice totals.
 */
@Service
public class TaxCalculationService {

    private static final Logger log = LoggerFactory.getLogger(TaxCalculationService.class);

    // Standard default GST / VAT rate: 18.00% (0.18)
    public static final BigDecimal DEFAULT_TAX_RATE = new BigDecimal("0.18");

    public record TaxableLineItem(
            UUID itemId,
            String itemName,
            long netAmountCents,
            BigDecimal taxRate
    ) {
        public TaxableLineItem(UUID itemId, String itemName, long netAmountCents) {
            this(itemId, itemName, netAmountCents, DEFAULT_TAX_RATE);
        }
    }

    public record LineItemTax(
            UUID itemId,
            String itemName,
            long netAmountCents,
            long taxCents,
            long grossAmountCents,
            BigDecimal effectiveTaxRate
    ) {}

    public record TaxCalculationResult(
            List<LineItemTax> lineItems,
            long totalNetCents,
            long totalTaxCents,
            long totalGrossCents,
            BigDecimal fractionalRemainderCents
    ) {}

    /**
     * Calculates tax across a list of line items using Banker's rounding (HALF_EVEN)
     * and carries over the fractional cent remainder to subsequent items.
     *
     * @param items List of taxable items
     * @param fallbackTaxRate Default tax rate if item.taxRate() is null
     * @return TaxCalculationResult guaranteeing sum(lineItems.taxCents) == totalTaxCents
     */
    public TaxCalculationResult calculateTaxes(List<TaxableLineItem> items, BigDecimal fallbackTaxRate) {
        if (items == null || items.isEmpty()) {
            return new TaxCalculationResult(List.of(), 0, 0, 0, BigDecimal.ZERO);
        }

        BigDecimal rateToUse = fallbackTaxRate != null ? fallbackTaxRate : DEFAULT_TAX_RATE;
        List<LineItemTax> lineItemTaxes = new ArrayList<>();

        long runningNetCents = 0;
        long runningTaxCents = 0;
        BigDecimal remainderCarryOver = BigDecimal.ZERO;

        for (TaxableLineItem item : items) {
            BigDecimal itemRate = item.taxRate() != null ? item.taxRate() : rateToUse;
            long netCents = item.netAmountCents();
            runningNetCents += netCents;

            // Compute exact tax with accumulated remainder carry-over
            BigDecimal netAmount = BigDecimal.valueOf(netCents);
            BigDecimal unroundedTax = netAmount.multiply(itemRate).add(remainderCarryOver);

            // Banker's rounding (HALF_EVEN) to integer cents
            BigDecimal roundedTax = unroundedTax.setScale(0, RoundingMode.HALF_EVEN);
            long lineTaxCents = roundedTax.longValue();

            // Carry over fractional remainder to next line item: (unrounded - rounded)
            remainderCarryOver = unroundedTax.subtract(roundedTax);

            runningTaxCents += lineTaxCents;
            long grossCents = netCents + lineTaxCents;

            lineItemTaxes.add(new LineItemTax(
                    item.itemId(),
                    item.itemName(),
                    netCents,
                    lineTaxCents,
                    grossCents,
                    itemRate
            ));
        }

        long totalGrossCents = runningNetCents + runningTaxCents;

        log.debug("Tax calculation completed: net={}, tax={}, gross={}, remainderCarryOver={}",
                runningNetCents, runningTaxCents, totalGrossCents, remainderCarryOver);

        return new TaxCalculationResult(
                lineItemTaxes,
                runningNetCents,
                runningTaxCents,
                totalGrossCents,
                remainderCarryOver
        );
    }

    /**
     * Convenience method for calculating tax on single gross or net subtotal.
     */
    public long calculateTaxForSubtotal(long subtotalCents, BigDecimal taxRate) {
        BigDecimal rate = taxRate != null ? taxRate : DEFAULT_TAX_RATE;
        return BigDecimal.valueOf(subtotalCents)
                .multiply(rate)
                .setScale(0, RoundingMode.HALF_EVEN)
                .longValue();
    }
}
