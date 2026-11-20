package com.instacommerce.checkout.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PriceDriftEvaluatorTest {

    @Test
    void allowsAnyNonNegativeTotalWhenExpectedTotalIsOmitted() {
        PriceDriftEvaluator.Evaluation result = PriceDriftEvaluator.evaluate(4500L, null, null);

        assertThat(result.withinTolerance()).isTrue();
        assertThat(result.driftCents()).isZero();
    }

    @Test
    void allowsPriceIncreaseAtExactToleranceBoundary() {
        PriceDriftEvaluator.Evaluation result = PriceDriftEvaluator.evaluate(10500L, 10000L, 500L);

        assertThat(result.withinTolerance()).isTrue();
        assertThat(result.driftCents()).isEqualTo(500L);
        assertThat(result.failureReason()).isNull();
    }

    @Test
    void rejectsPriceIncreaseExceedingExplicitTolerance() {
        PriceDriftEvaluator.Evaluation result = PriceDriftEvaluator.evaluate(10501L, 10000L, 500L);

        assertThat(result.withinTolerance()).isFalse();
        assertThat(result.driftCents()).isEqualTo(501L);
        assertThat(result.failureReason()).contains("Price increased beyond tolerance");
    }

    @Test
    void rejectsAnyPriceIncreaseWhenToleranceNotSpecified() {
        PriceDriftEvaluator.Evaluation result = PriceDriftEvaluator.evaluate(10001L, 10000L, null);

        assertThat(result.withinTolerance()).isFalse();
        assertThat(result.maxDriftCents()).isZero();
    }

    @Test
    void allowsFavorablePriceDecrease() {
        PriceDriftEvaluator.Evaluation result = PriceDriftEvaluator.evaluate(8500L, 10000L, 100L);

        assertThat(result.withinTolerance()).isTrue();
        assertThat(result.driftCents()).isEqualTo(-1500L);
    }

    @Test
    void rejectsNegativeAmountsOrTolerance() {
        assertThat(PriceDriftEvaluator.evaluate(-1L, 100L, 10L).withinTolerance()).isFalse();
        assertThat(PriceDriftEvaluator.evaluate(100L, -1L, 10L).withinTolerance()).isFalse();
        assertThat(PriceDriftEvaluator.evaluate(100L, 100L, -5L).withinTolerance()).isFalse();
    }
}
