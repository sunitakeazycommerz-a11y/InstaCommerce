package com.instacommerce.order.domain.model;

public final class OrderRefundCalculator {

    private OrderRefundCalculator() {
    }

    public record PartialRefundBreakdown(
        long grossRemovedCents,
        long discountAdjustmentCents,
        long refundCents,
        long updatedSubtotalCents,
        long updatedDiscountCents,
        long updatedTotalCents
    ) {
    }

    public static PartialRefundBreakdown calculateForRemovedGross(
        long orderSubtotalCents,
        long orderDiscountCents,
        long orderTotalCents,
        long grossRemovedCents
    ) {
        if (grossRemovedCents < 0) {
            throw new IllegalArgumentException("grossRemovedCents must not be negative: " + grossRemovedCents);
        }
        if (orderTotalCents < 0) {
            throw new IllegalArgumentException("orderTotalCents must not be negative: " + orderTotalCents);
        }
        if (grossRemovedCents == 0 || orderTotalCents == 0) {
            return new PartialRefundBreakdown(
                0L, 0L, 0L, Math.max(0L, orderSubtotalCents), Math.max(0L, orderDiscountCents), orderTotalCents);
        }
        long effectiveRemovedGross = orderSubtotalCents > 0
            ? Math.min(grossRemovedCents, orderSubtotalCents)
            : grossRemovedCents;
        long discountAdjustment = 0L;
        if (orderSubtotalCents > 0 && orderDiscountCents > 0) {
            discountAdjustment = Math.min(
                orderDiscountCents,
                (effectiveRemovedGross * orderDiscountCents) / orderSubtotalCents);
        }
        long netReduction = Math.max(0L, effectiveRemovedGross - discountAdjustment);
        long boundedRefund = Math.min(orderTotalCents, netReduction);
        long updatedSubtotal = orderSubtotalCents > 0
            ? Math.max(0L, orderSubtotalCents - effectiveRemovedGross)
            : 0L;
        long updatedDiscount = Math.max(0L, orderDiscountCents - discountAdjustment);
        long updatedTotal = Math.max(0L, orderTotalCents - boundedRefund);
        return new PartialRefundBreakdown(
            effectiveRemovedGross,
            discountAdjustment,
            boundedRefund,
            updatedSubtotal,
            updatedDiscount,
            updatedTotal
        );
    }
}
