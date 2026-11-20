package com.instacommerce.checkout.domain;

public final class PriceDriftEvaluator {

    private PriceDriftEvaluator() {
    }

    public record Evaluation(
        boolean withinTolerance,
        long actualTotalCents,
        long expectedTotalCents,
        long driftCents,
        long maxDriftCents,
        String failureReason
    ) {
    }

    public static Evaluation evaluate(long actualTotalCents, Long expectedTotalCents, Long maxPriceDriftCents) {
        if (actualTotalCents < 0) {
            return new Evaluation(
                false,
                actualTotalCents,
                expectedTotalCents != null ? expectedTotalCents : 0L,
                0L,
                0L,
                "Calculated total cannot be negative: " + actualTotalCents
            );
        }
        if (maxPriceDriftCents != null && maxPriceDriftCents < 0) {
            return new Evaluation(
                false,
                actualTotalCents,
                expectedTotalCents != null ? expectedTotalCents : actualTotalCents,
                0L,
                maxPriceDriftCents,
                "Price drift tolerance cannot be negative: " + maxPriceDriftCents
            );
        }
        long allowedDrift = maxPriceDriftCents != null ? maxPriceDriftCents : 0L;
        if (expectedTotalCents == null) {
            return new Evaluation(true, actualTotalCents, actualTotalCents, 0L, allowedDrift, null);
        }
        if (expectedTotalCents < 0) {
            return new Evaluation(
                false,
                actualTotalCents,
                expectedTotalCents,
                0L,
                allowedDrift,
                "Expected total cannot be negative: " + expectedTotalCents
            );
        }
        long drift = actualTotalCents - expectedTotalCents;
        if (drift > allowedDrift) {
            return new Evaluation(
                false,
                actualTotalCents,
                expectedTotalCents,
                drift,
                allowedDrift,
                "Price increased beyond tolerance: expected=" + expectedTotalCents
                    + ", actual=" + actualTotalCents
                    + ", drift=" + drift
                    + ", maxDrift=" + allowedDrift
            );
        }
        return new Evaluation(true, actualTotalCents, expectedTotalCents, drift, allowedDrift, null);
    }
}
