package com.instacommerce.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SurgePricingServiceTest {

    private SurgePricingService surgePricingService;
    private static final UUID STORE_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        surgePricingService = new SurgePricingService();
    }

    @Test
    @DisplayName("Normal condition (utilization < 85%, no rain) applies base delivery fee without surge")
    void normalConditionNoSurge() {
        surgePricingService.recordStoreSignal(STORE_ID, 0.65, 5, false);

        SurgePricingService.SurgeAssessment assessment = surgePricingService.evaluateSurge(STORE_ID);

        assertThat(assessment.isSurgeActive()).isFalse();
        assertThat(assessment.surgeMultiplier()).isEqualTo(BigDecimal.ONE);
        assertThat(assessment.surgeDeliveryFeeCents()).isEqualTo(3000L);
        assertThat(assessment.customerBadgeText()).isNull();
    }

    @Test
    @DisplayName("High demand surge triggers when fleet capacity reaches 85%")
    void highFleetUtilizationTriggersSurge() {
        surgePricingService.recordStoreSignal(STORE_ID, 0.88, 10, false);

        SurgePricingService.SurgeAssessment assessment = surgePricingService.evaluateSurge(STORE_ID);

        assertThat(assessment.isSurgeActive()).isTrue();
        assertThat(assessment.surgeMultiplier()).isEqualTo(new BigDecimal("1.25"));
        assertThat(assessment.surgeDeliveryFeeCents()).isEqualTo(3750L);
        assertThat(assessment.customerBadgeText()).contains("High Demand Surge");
    }

    @Test
    @DisplayName("Rain surge triggers when severe weather is detected")
    void rainSurgeTriggers() {
        surgePricingService.recordStoreSignal(STORE_ID, 0.50, 4, true);

        SurgePricingService.SurgeAssessment assessment = surgePricingService.evaluateSurge(STORE_ID);

        assertThat(assessment.isSurgeActive()).isTrue();
        assertThat(assessment.surgeMultiplier()).isEqualTo(new BigDecimal("1.30"));
        assertThat(assessment.surgeDeliveryFeeCents()).isEqualTo(3900L);
        assertThat(assessment.customerBadgeText()).contains("Weather Surge");
    }

    @Test
    @DisplayName("Combined severe weather and high fleet demand caps at ethical ceiling (2.00x max)")
    void combinedSurgeEnforcesCeiling() {
        surgePricingService.recordStoreSignal(STORE_ID, 0.98, 30, true);

        SurgePricingService.SurgeAssessment assessment = surgePricingService.evaluateSurge(STORE_ID);

        assertThat(assessment.isSurgeActive()).isTrue();
        assertThat(assessment.surgeMultiplier()).isLessThanOrEqualTo(SurgePricingService.MAX_SURGE_MULTIPLIER);
        assertThat(assessment.surgeDeliveryFeeCents()).isLessThanOrEqualTo(6000L);
    }
}
