package com.instacommerce.featureflag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ExperimentEvaluationServiceTest {

    @Test
    void testComputeBucketPercentDeterministicConsistency() {
        int bucket = ExperimentEvaluationService.computeBucketPercent("user_123", "exp_pricing_v1");
        assertEquals(79, bucket, "Deterministic floorMod MurmurHash3 percent bucket must be 79");

        int bucket2 = ExperimentEvaluationService.computeBucketPercent("user_456", "exp_ranking_v2");
        assertTrue(bucket2 >= 0 && bucket2 < 100);
    }
}
