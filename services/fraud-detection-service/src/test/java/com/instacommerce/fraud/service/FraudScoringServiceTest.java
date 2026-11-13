package com.instacommerce.fraud.service;

import com.instacommerce.fraud.config.FraudOperationalMode;
import com.instacommerce.fraud.domain.model.FraudAction;
import com.instacommerce.fraud.domain.model.FraudRule;
import com.instacommerce.fraud.domain.model.RiskLevel;
import com.instacommerce.fraud.domain.model.FraudSignal;
import com.instacommerce.fraud.dto.request.FraudCheckRequest;
import com.instacommerce.fraud.dto.response.FraudCheckResponse;
import com.instacommerce.fraud.repository.FraudRuleRepository;
import com.instacommerce.fraud.repository.FraudSignalRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FraudScoringServiceTest {

    @Mock
    private FraudRuleRepository fraudRuleRepository;

    @Mock
    private FraudSignalRepository fraudSignalRepository;

    @Mock
    private RuleEvaluationService ruleEvaluationService;

    @Mock
    private BlocklistService blocklistService;

    @Mock
    private OutboxService outboxService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private MeterRegistry meterRegistry;
    private FraudScoringService fraudScoringService;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(fraudSignalRepository.save(any(FraudSignal.class))).thenAnswer(inv -> {
            FraudSignal s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });

        meterRegistry = new SimpleMeterRegistry();
        fraudScoringService = serviceWithMode(FraudOperationalMode.AUTO_BLOCK);
    }

    private FraudScoringService serviceWithMode(FraudOperationalMode mode) {
        return new FraudScoringService(
                fraudRuleRepository,
                fraudSignalRepository,
                ruleEvaluationService,
                blocklistService,
                outboxService,
                redisTemplate,
                meterRegistry,
                mode
        );
    }

    @Test
    @DisplayName("Blocks checkout when reverse-ETL cached fraud score > 0.85 (Issue #659)")
    void shouldBlockCheckoutWhenCachedFraudScoreExceedsThreshold() {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        when(blocklistService.isBlocked(anyString(), anyString())).thenReturn(false);
        when(valueOperations.get("fraud:score:" + userId)).thenReturn("{\"fraud_risk_score\": 0.94, \"model_version\": \"v2\"}");

        FraudCheckRequest request = new FraudCheckRequest(
                userId,
                orderId,
                49900L,
                "device-fingerprint-abc",
                "192.168.1.1",
                12.9716,
                77.5946,
                3,
                "CARD",
                false
        );

        FraudCheckResponse response = fraudScoringService.scoreTransaction(request);

        assertThat(response).isNotNull();
        assertThat(response.action()).isEqualTo(FraudAction.BLOCK.name());
        assertThat(response.riskLevel()).isEqualTo(RiskLevel.CRITICAL.name());
        assertThat(response.score()).isEqualTo(94);
        assertThat(response.rulesTriggered()).contains("HIGH_FRAUD_RISK_SCORE");
    }

    @Test
    @DisplayName("Allows transaction to proceed to rule evaluation when fraud score <= 0.85")
    void shouldProceedWhenCachedFraudScoreUnderThreshold() {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        when(blocklistService.isBlocked(anyString(), anyString())).thenReturn(false);
        when(valueOperations.get("fraud:score:" + userId)).thenReturn("{\"fraud_risk_score\": 0.35}");
        when(fraudRuleRepository.findActiveOrderByPriority()).thenReturn(Collections.emptyList());

        FraudCheckRequest request = new FraudCheckRequest(
                userId,
                orderId,
                15000L,
                "device-clean",
                "10.0.0.1",
                12.9716,
                77.5946,
                2,
                "UPI",
                false
        );

        FraudCheckResponse response = fraudScoringService.scoreTransaction(request);

        assertThat(response).isNotNull();
        assertThat(response.action()).isEqualTo(FraudAction.ALLOW.name());
        assertThat(response.riskLevel()).isEqualTo(RiskLevel.LOW.name());
        assertThat(response.rulesTriggered()).doesNotContain("HIGH_FRAUD_RISK_SCORE");
    }

    @Test
    @DisplayName("Gracefully falls back to normal scoring when Redis is unavailable")
    void shouldFallbackGracefullyWhenRedisFails() {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        when(blocklistService.isBlocked(anyString(), anyString())).thenReturn(false);
        when(valueOperations.get("fraud:score:" + userId)).thenThrow(new RuntimeException("Redis connection timeout"));
        when(fraudRuleRepository.findActiveOrderByPriority()).thenReturn(Collections.emptyList());

        FraudCheckRequest request = new FraudCheckRequest(
                userId,
                orderId,
                20000L,
                "device-xyz",
                "127.0.0.1",
                12.9716,
                77.5946,
                1,
                "COD",
                true
        );

        FraudCheckResponse response = fraudScoringService.scoreTransaction(request);

        assertThat(response).isNotNull();
        assertThat(response.action()).isEqualTo(FraudAction.ALLOW.name());
        assertThat(meterRegistry.counter("fraud.scoring.errors",
                "stage", "redis_cache",
                "mode", "AUTO_BLOCK",
                "fallback_action", "FALLBACK_RULES").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Defaults to AUTO_BLOCK fail-closed behavior when operational mode value is invalid")
    void shouldFailClosedWhenOperationalModeIsUnrecognized() {
        FraudScoringService fallbackModeService = new FraudScoringService(
                fraudRuleRepository,
                fraudSignalRepository,
                ruleEvaluationService,
                blocklistService,
                outboxService,
                null,
                null,
                null,
                "UNRECOGNIZED_MODE"
        );
        FraudCheckRequest request = sampleRequest();
        FraudRule blockRule = buildRule("block-rule", "BLOCK", 90);

        when(blocklistService.isBlocked(anyString(), anyString())).thenReturn(false);
        when(fraudRuleRepository.findActiveOrderByPriority()).thenReturn(List.of(blockRule));
        when(ruleEvaluationService.evaluateRule(blockRule, request))
                .thenThrow(new RuntimeException("Rule failure"));

        FraudCheckResponse response = fallbackModeService.scoreTransaction(request);

        assertThat(response.action()).isEqualTo(FraudAction.BLOCK.name());
        assertThat(response.rulesTriggered()).contains("RULE_EVALUATION_ERROR:block-rule");
    }

    @Test
    @DisplayName("Fails closed to REVIEW when non-blocking rule throws in AUTO_BLOCK mode")
    void shouldFailClosedToReviewWhenNonBlockingRuleThrowsInAutoBlockMode() {
        FraudCheckRequest request = sampleRequest();
        FraudRule velocityRule = buildRule("velocity-rule", "FLAG", 20);

        when(blocklistService.isBlocked(anyString(), anyString())).thenReturn(false);
        when(fraudRuleRepository.findActiveOrderByPriority()).thenReturn(List.of(velocityRule));
        when(ruleEvaluationService.evaluateRule(velocityRule, request))
                .thenThrow(new IllegalStateException("Velocity store timeout"));

        FraudCheckResponse response = fraudScoringService.scoreTransaction(request);

        assertThat(response.action()).isEqualTo(FraudAction.REVIEW.name());
        assertThat(response.riskLevel()).isEqualTo(RiskLevel.HIGH.name());
        assertThat(response.score()).isGreaterThanOrEqualTo(RiskLevel.HIGH.getMinScore());
        assertThat(response.rulesTriggered()).contains("RULE_EVALUATION_ERROR:velocity-rule");
        assertThat(response.signalId()).isNotNull();

        ArgumentCaptor<FraudSignal> signalCaptor = ArgumentCaptor.forClass(FraudSignal.class);
        verify(fraudSignalRepository).save(signalCaptor.capture());
        assertThat(signalCaptor.getValue().getActionTaken()).isEqualTo(FraudAction.REVIEW.name());
        verify(outboxService).publish(eq("FraudSignal"), anyString(), eq("FraudDetected"), anyMap());

        double errorCount = meterRegistry.counter("fraud.scoring.errors",
                "stage", "rule_evaluation",
                "mode", "AUTO_BLOCK",
                "fallback_action", "REVIEW").count();
        assertThat(errorCount).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Fails closed to BLOCK when BLOCK rule throws in AUTO_BLOCK mode")
    void shouldFailClosedToBlockWhenBlockRuleThrowsInAutoBlockMode() {
        FraudCheckRequest request = sampleRequest();
        FraudRule blockRule = buildRule("high-risk-rule", "BLOCK", 90);

        when(blocklistService.isBlocked(anyString(), anyString())).thenReturn(false);
        when(fraudRuleRepository.findActiveOrderByPriority()).thenReturn(List.of(blockRule));
        when(ruleEvaluationService.evaluateRule(blockRule, request))
                .thenThrow(new RuntimeException("Rule engine panic"));

        FraudCheckResponse response = fraudScoringService.scoreTransaction(request);

        assertThat(response.action()).isEqualTo(FraudAction.BLOCK.name());
        assertThat(response.riskLevel()).isEqualTo(RiskLevel.CRITICAL.name());
        assertThat(response.score()).isGreaterThanOrEqualTo(RiskLevel.CRITICAL.getMinScore());
        assertThat(response.rulesTriggered()).contains("RULE_EVALUATION_ERROR:high-risk-rule");
        verify(outboxService).publish(eq("FraudSignal"), anyString(), eq("FraudDetected"), anyMap());

        double errorCount = meterRegistry.counter("fraud.scoring.errors",
                "stage", "rule_evaluation",
                "mode", "AUTO_BLOCK",
                "fallback_action", "BLOCK").count();
        assertThat(errorCount).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Continues evaluating remaining rules after a rule exception and escalates")
    void shouldContinueEvaluatingRemainingRulesAfterRuleException() {
        FraudCheckRequest request = sampleRequest();
        FraudRule flakyRule = buildRule("flaky-rule", "FLAG", 15);
        FraudRule blockRule = buildRule("confirmed-fraud", "BLOCK", 90);

        when(blocklistService.isBlocked(anyString(), anyString())).thenReturn(false);
        when(fraudRuleRepository.findActiveOrderByPriority()).thenReturn(List.of(flakyRule, blockRule));
        when(ruleEvaluationService.evaluateRule(flakyRule, request))
                .thenThrow(new RuntimeException("Transient error"));
        when(ruleEvaluationService.evaluateRule(blockRule, request)).thenReturn(true);

        FraudCheckResponse response = fraudScoringService.scoreTransaction(request);

        assertThat(response.action()).isEqualTo(FraudAction.BLOCK.name());
        assertThat(response.riskLevel()).isEqualTo(RiskLevel.CRITICAL.name());
        assertThat(response.score()).isEqualTo(90);
        assertThat(response.rulesTriggered())
                .containsExactly("RULE_EVALUATION_ERROR:flaky-rule", "confirmed-fraud");
    }

    @Test
    @DisplayName("Fails closed to BLOCK when blocklist check throws in AUTO_BLOCK mode")
    void shouldFailClosedToBlockWhenBlocklistThrowsInAutoBlockMode() {
        FraudCheckRequest request = sampleRequest();
        when(blocklistService.isBlocked(anyString(), anyString()))
                .thenThrow(new RuntimeException("Blocklist DB down"));

        FraudCheckResponse response = fraudScoringService.scoreTransaction(request);

        assertThat(response.action()).isEqualTo(FraudAction.BLOCK.name());
        assertThat(response.riskLevel()).isEqualTo(RiskLevel.CRITICAL.name());
        assertThat(response.score()).isEqualTo(100);
        assertThat(response.rulesTriggered()).contains("BLOCKLIST_EVALUATION_ERROR");
        assertThat(response.signalId()).isNotNull();
        verify(outboxService).publish(eq("FraudSignal"), anyString(), eq("FraudDetected"), anyMap());

        double errorCount = meterRegistry.counter("fraud.scoring.errors",
                "stage", "blocklist",
                "mode", "AUTO_BLOCK",
                "fallback_action", "BLOCK").count();
        assertThat(errorCount).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Fails closed to BLOCK when active rule loading throws in AUTO_BLOCK mode")
    void shouldFailClosedToBlockWhenRuleLoadThrowsInAutoBlockMode() {
        FraudCheckRequest request = sampleRequest();
        when(blocklistService.isBlocked(anyString(), anyString())).thenReturn(false);
        when(fraudRuleRepository.findActiveOrderByPriority())
                .thenThrow(new RuntimeException("Rule repository unavailable"));

        FraudCheckResponse response = fraudScoringService.scoreTransaction(request);

        assertThat(response.action()).isEqualTo(FraudAction.BLOCK.name());
        assertThat(response.riskLevel()).isEqualTo(RiskLevel.CRITICAL.name());
        assertThat(response.score()).isEqualTo(100);
        assertThat(response.rulesTriggered()).contains("RULE_LOAD_ERROR");
        verify(outboxService).publish(eq("FraudSignal"), anyString(), eq("FraudDetected"), anyMap());

        double errorCount = meterRegistry.counter("fraud.scoring.errors",
                "stage", "rule_load",
                "mode", "AUTO_BLOCK",
                "fallback_action", "BLOCK").count();
        assertThat(errorCount).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Still returns fail-closed response when fallback signal persistence fails")
    void shouldStillFailClosedWhenFallbackPersistenceFails() {
        FraudCheckRequest request = sampleRequest();
        when(blocklistService.isBlocked(anyString(), anyString()))
                .thenThrow(new RuntimeException("Blocklist outage"));
        when(fraudSignalRepository.save(any(FraudSignal.class)))
                .thenThrow(new RuntimeException("Signal DB unavailable"));

        FraudCheckResponse response = fraudScoringService.scoreTransaction(request);

        assertThat(response.action()).isEqualTo(FraudAction.BLOCK.name());
        assertThat(response.riskLevel()).isEqualTo(RiskLevel.CRITICAL.name());
        assertThat(response.score()).isEqualTo(100);
        assertThat(response.rulesTriggered()).contains("BLOCKLIST_EVALUATION_ERROR");
        assertThat(response.signalId()).isNull();

        double persistErrorCount = meterRegistry.counter("fraud.scoring.errors",
                "stage", "signal_persist",
                "mode", "AUTO_BLOCK",
                "fallback_action", "BLOCK").count();
        assertThat(persistErrorCount).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Fails closed to REVIEW on errors in MANUAL_REVIEW mode")
    void shouldFailClosedToReviewOnErrorsInManualReviewMode() {
        FraudScoringService manualReviewService = serviceWithMode(FraudOperationalMode.MANUAL_REVIEW);
        FraudCheckRequest request = sampleRequest();
        FraudRule blockRule = buildRule("critical-rule", "BLOCK", 90);

        when(blocklistService.isBlocked(anyString(), anyString())).thenReturn(false);
        when(fraudRuleRepository.findActiveOrderByPriority()).thenReturn(List.of(blockRule));
        when(ruleEvaluationService.evaluateRule(blockRule, request))
                .thenThrow(new RuntimeException("Rule evaluation failed"));

        FraudCheckResponse ruleErrorResponse = manualReviewService.scoreTransaction(request);

        assertThat(ruleErrorResponse.action()).isEqualTo(FraudAction.REVIEW.name());
        assertThat(ruleErrorResponse.rulesTriggered()).contains("RULE_EVALUATION_ERROR:critical-rule");

        when(blocklistService.isBlocked(anyString(), anyString()))
                .thenThrow(new RuntimeException("Blocklist failed"));
        FraudCheckResponse blocklistErrorResponse = manualReviewService.scoreTransaction(request);

        assertThat(blocklistErrorResponse.action()).isEqualTo(FraudAction.REVIEW.name());
        assertThat(blocklistErrorResponse.riskLevel()).isEqualTo(RiskLevel.HIGH.name());
        assertThat(blocklistErrorResponse.rulesTriggered()).contains("BLOCKLIST_EVALUATION_ERROR");
    }

    @Test
    @DisplayName("Allows transaction in SHADOW mode while still recording error metrics")
    void shouldAllowInShadowModeWhenRuleOrDependencyThrows() {
        FraudScoringService shadowService = serviceWithMode(FraudOperationalMode.SHADOW);
        FraudCheckRequest request = sampleRequest();
        FraudRule blockRule = buildRule("shadow-broken-rule", "BLOCK", 95);

        when(blocklistService.isBlocked(anyString(), anyString())).thenReturn(false);
        when(fraudRuleRepository.findActiveOrderByPriority()).thenReturn(List.of(blockRule));
        when(ruleEvaluationService.evaluateRule(blockRule, request))
                .thenThrow(new RuntimeException("Rule failure"));

        FraudCheckResponse ruleErrResponse = shadowService.scoreTransaction(request);

        assertThat(ruleErrResponse.action()).isEqualTo(FraudAction.ALLOW.name());
        assertThat(ruleErrResponse.riskLevel()).isEqualTo(RiskLevel.LOW.name());
        assertThat(ruleErrResponse.score()).isZero();
        assertThat(ruleErrResponse.signalId()).isNull();
        assertThat(meterRegistry.counter("fraud.scoring.errors",
                "stage", "rule_evaluation",
                "mode", "SHADOW",
                "fallback_action", "ALLOW").count()).isEqualTo(1.0);

        when(blocklistService.isBlocked(anyString(), anyString()))
                .thenThrow(new RuntimeException("Blocklist failure"));
        FraudCheckResponse blocklistErrResponse = shadowService.scoreTransaction(request);

        assertThat(blocklistErrResponse.action()).isEqualTo(FraudAction.ALLOW.name());
        assertThat(blocklistErrResponse.score()).isZero();
        assertThat(meterRegistry.counter("fraud.scoring.errors",
                "stage", "blocklist",
                "mode", "SHADOW",
                "fallback_action", "ALLOW").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Bypasses scoring completely in PASS_THROUGH mode")
    void shouldBypassScoringInPassThroughMode() {
        FraudScoringService passThroughService = serviceWithMode(FraudOperationalMode.PASS_THROUGH);
        FraudCheckResponse response = passThroughService.scoreTransaction(sampleRequest());

        assertThat(response.action()).isEqualTo(FraudAction.ALLOW.name());
        assertThat(response.score()).isZero();
        assertThat(response.signalId()).isNull();
        verifyNoInteractions(blocklistService, fraudRuleRepository, ruleEvaluationService);
    }

    private static FraudCheckRequest sampleRequest() {
        return new FraudCheckRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                25000L,
                "device-123",
                "198.51.100.1",
                12.9716,
                77.5946,
                2,
                "CARD",
                false
        );
    }

    private static FraudRule buildRule(String name, String action, int scoreImpact) {
        FraudRule rule = new FraudRule();
        rule.setId(UUID.randomUUID());
        rule.setName(name);
        rule.setRuleType("VELOCITY");
        rule.setAction(action);
        rule.setScoreImpact(scoreImpact);
        rule.setActive(true);
        return rule;
    }
}
