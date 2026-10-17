package com.instacommerce.fraud.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.instacommerce.fraud.domain.model.FraudRule;
import com.instacommerce.fraud.dto.request.FraudCheckRequest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RuleEvaluationServiceTest {

    @Mock
    private VelocityService velocityService;

    private RuleEvaluationService evaluationService;

    @BeforeEach
    void setUp() {
        evaluationService = new RuleEvaluationService(velocityService);
    }

    @Test
    @DisplayName("evaluateRule triggers VELOCITY rule when threshold is reached")
    void evaluateRule_velocity_triggersWhenThresholdMet() {
        UUID userId = UUID.randomUUID();
        FraudRule rule = new FraudRule();
        rule.setRuleType("VELOCITY");
        rule.setConditionJson(Map.of(
            "entityType", "USER",
            "counterType", "ORDERS_1H",
            "threshold", 5
        ));

        FraudCheckRequest request = new FraudCheckRequest(
            userId, UUID.randomUUID(), 2000L, "dev-1", "10.0.0.1", 12.97, 77.59, 3, "CARD", false
        );

        when(velocityService.getCount("USER", userId.toString(), "ORDERS_1H")).thenReturn(5L);

        boolean triggered = evaluationService.evaluateRule(rule, request);
        assertThat(triggered).isTrue();
    }

    @Test
    @DisplayName("evaluateRule does not trigger VELOCITY rule when below threshold")
    void evaluateRule_velocity_doesNotTriggerWhenBelowThreshold() {
        UUID userId = UUID.randomUUID();
        FraudRule rule = new FraudRule();
        rule.setRuleType("VELOCITY");
        rule.setConditionJson(Map.of(
            "entityType", "USER",
            "counterType", "ORDERS_1H",
            "threshold", 5
        ));

        FraudCheckRequest request = new FraudCheckRequest(
            userId, UUID.randomUUID(), 2000L, "dev-1", "10.0.0.1", 12.97, 77.59, 3, "CARD", false
        );

        when(velocityService.getCount("USER", userId.toString(), "ORDERS_1H")).thenReturn(3L);

        boolean triggered = evaluationService.evaluateRule(rule, request);
        assertThat(triggered).isFalse();
    }

    @Test
    @DisplayName("evaluateRule triggers AMOUNT rule when order exceeds maxAmountCents")
    void evaluateRule_amount_triggersWhenExceeded() {
        FraudRule rule = new FraudRule();
        rule.setRuleType("AMOUNT");
        rule.setConditionJson(Map.of(
            "maxAmountCents", 100000L,
            "newUsersOnly", false
        ));

        FraudCheckRequest request = new FraudCheckRequest(
            UUID.randomUUID(), UUID.randomUUID(), 150000L, "dev-1", "10.0.0.1", 12.97, 77.59, 5, "UPI", false
        );

        boolean triggered = evaluationService.evaluateRule(rule, request);
        assertThat(triggered).isTrue();
    }

    @Test
    @DisplayName("evaluateRule returns false for unknown rule type")
    void evaluateRule_unknownType_returnsFalse() {
        FraudRule rule = new FraudRule();
        rule.setRuleType("UNKNOWN_CUSTOM_TYPE");
        rule.setConditionJson(Map.of());

        FraudCheckRequest request = new FraudCheckRequest(
            UUID.randomUUID(), UUID.randomUUID(), 1000L, "dev-1", "10.0.0.1", 12.97, 77.59, 1, "UPI", false
        );

        boolean triggered = evaluationService.evaluateRule(rule, request);
        assertThat(triggered).isFalse();
    }
}
