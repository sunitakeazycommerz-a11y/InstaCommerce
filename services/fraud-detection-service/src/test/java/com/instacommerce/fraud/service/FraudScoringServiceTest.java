package com.instacommerce.fraud.service;

import com.instacommerce.fraud.domain.model.FraudAction;
import com.instacommerce.fraud.domain.model.RiskLevel;
import com.instacommerce.fraud.domain.model.FraudSignal;
import com.instacommerce.fraud.dto.request.FraudCheckRequest;
import com.instacommerce.fraud.dto.response.FraudCheckResponse;
import com.instacommerce.fraud.repository.FraudRuleRepository;
import com.instacommerce.fraud.repository.FraudSignalRepository;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
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

    private FraudScoringService fraudScoringService;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(fraudSignalRepository.save(any(FraudSignal.class))).thenAnswer(inv -> {
            FraudSignal s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });

        fraudScoringService = new FraudScoringService(
                fraudRuleRepository,
                fraudSignalRepository,
                ruleEvaluationService,
                blocklistService,
                outboxService,
                redisTemplate
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
    }
}
