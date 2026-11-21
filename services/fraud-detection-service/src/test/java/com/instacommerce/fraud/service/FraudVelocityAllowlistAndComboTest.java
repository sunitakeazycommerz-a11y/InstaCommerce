package com.instacommerce.fraud.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.instacommerce.fraud.domain.model.AllowlistedEntity;
import com.instacommerce.fraud.domain.model.FraudAction;
import com.instacommerce.fraud.domain.model.FraudRule;
import com.instacommerce.fraud.domain.model.FraudSignal;
import com.instacommerce.fraud.dto.request.BlockRequest;
import com.instacommerce.fraud.dto.request.FraudCheckRequest;
import com.instacommerce.fraud.dto.response.FraudCheckResponse;
import com.instacommerce.fraud.exception.ApiException;
import com.instacommerce.fraud.repository.AllowlistRepository;
import com.instacommerce.fraud.repository.FraudRuleRepository;
import com.instacommerce.fraud.repository.FraudSignalRepository;
import com.instacommerce.fraud.repository.VelocityCounterRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class FraudVelocityAllowlistAndComboTest {

    @Test
    void velocityServiceUsesRollingSubBucketsAcrossHourBoundaryAndSupports5MinuteBursts() {
        VelocityCounterRepository repo = mock(VelocityCounterRepository.class);
        AtomicReference<Instant> clockNow = new AtomicReference<>(Instant.parse("2026-11-19T10:59:30Z"));
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return clockNow.get();
            }
        };

        VelocityService velocityService = new VelocityService(repo, clock);

        velocityService.incrementCounter("user", "u-1", "orders_1h");
        verify(repo).upsertCounter(
            "USER",
            "u-1",
            "ORDERS_1H",
            1L,
            Instant.parse("2026-11-19T10:55:00Z"),
            Instant.parse("2026-11-19T11:55:00Z")
        );

        velocityService.incrementCounter("USER", "u-1", "ORDERS_5M");
        verify(repo).upsertCounter(
            "USER",
            "u-1",
            "ORDERS_5M",
            1L,
            Instant.parse("2026-11-19T10:59:00Z"),
            Instant.parse("2026-11-19T11:04:00Z")
        );
    }

    @Test
    void ruleEvaluatorAndValidatorSupportCombinationAndBurstPatterns() {
        RuleConditionValidator validator = new RuleConditionValidator();
        validator.validate("COMBINATION", Map.of(
            "paymentMethods", List.of("COD", "CRYPTO"),
            "minAmountCents", 50_000L,
            "requireNewDevice", true,
            "burstThreshold", 3
        ));
        assertThatThrownBy(() -> validator.validate("COMBINATION", Map.of("paymentMethods", 123)))
            .isInstanceOf(IllegalArgumentException.class);

        VelocityService velocityService = mock(VelocityService.class);
        RuleEvaluationService evaluationService = new RuleEvaluationService(velocityService);
        UUID userId = UUID.randomUUID();

        when(velocityService.getCount("USER", userId.toString(), "ORDERS_5M")).thenReturn(4L);
        when(velocityService.getCount("DEVICE", "dev-new", "ORDERS_24H")).thenReturn(0L);

        FraudRule comboRule = new FraudRule();
        comboRule.setName("CRYPTO_NEW_DEVICE_BURST");
        comboRule.setRuleType("COMBINATION");
        comboRule.setConditionJson(Map.of(
            "paymentMethods", List.of("CRYPTO", "PREPAID_CARD"),
            "minAmountCents", 50_000L,
            "requireNewDevice", true,
            "burstThreshold", 3
        ));

        FraudCheckRequest matchingReq = new FraudCheckRequest(
            userId, UUID.randomUUID(), 80_000L, "dev-new", "10.0.0.1", null, null, 2, "CRYPTO", false);
        assertThat(evaluationService.evaluateRule(comboRule, matchingReq)).isTrue();

        FraudRule burstPattern = new FraudRule();
        burstPattern.setName("BURST_5M");
        burstPattern.setRuleType("PATTERN");
        burstPattern.setConditionJson(Map.of("pattern", "VELOCITY_BURST", "threshold", 4));
        assertThat(evaluationService.evaluateRule(burstPattern, matchingReq)).isTrue();
    }

    @Test
    void enforcesBlocklistOverAllowlistPrecedenceAndScoresHighRiskPaymentNewDeviceCombinations() {
        FraudRuleRepository ruleRepo = mock(FraudRuleRepository.class);
        FraudSignalRepository signalRepo = mock(FraudSignalRepository.class);
        VelocityService velocityService = mock(VelocityService.class);
        RuleEvaluationService evalService = new RuleEvaluationService(velocityService);
        BlocklistService blocklistService = mock(BlocklistService.class);
        OutboxService outboxService = mock(OutboxService.class);
        AllowlistRepository allowlistRepository = mock(AllowlistRepository.class);
        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-19T12:00:00Z"), ZoneOffset.UTC);
        AllowlistService allowlistService = new AllowlistService(allowlistRepository, fixedClock);

        when(signalRepo.save(any(FraudSignal.class))).thenAnswer(inv -> {
            FraudSignal sig = inv.getArgument(0);
            sig.setId(UUID.randomUUID());
            return sig;
        });

        FraudScoringService scoringService = new FraudScoringService(
            ruleRepo, signalRepo, evalService, blocklistService, allowlistService,
            outboxService, null, null, null, null, "AUTO_BLOCK");

        UUID userId = UUID.randomUUID();
        AllowlistedEntity activeAllow = new AllowlistedEntity();
        activeAllow.setEntityType("USER");
        activeAllow.setEntityValue(userId.toString());
        activeAllow.setActive(true);
        activeAllow.setExpiresAt(Instant.parse("2026-11-20T00:00:00Z"));
        when(allowlistRepository.findActiveAllowlistEntry("USER", userId.toString()))
            .thenReturn(Optional.of(activeAllow));

        when(blocklistService.isBlocked("USER", userId.toString())).thenReturn(true);
        FraudCheckRequest req = new FraudCheckRequest(
            userId, UUID.randomUUID(), 200_000L, "dev-1", "10.0.0.1", null, null, 1, "CRYPTO", true);
        FraudCheckResponse blockedResp = scoringService.scoreTransaction(req);
        assertThat(blockedResp.action()).isEqualTo(FraudAction.BLOCK.name());
        assertThat(blockedResp.rulesTriggered()).contains("BLOCKLIST_HIT");

        when(blocklistService.isBlocked("USER", userId.toString())).thenReturn(false);
        FraudCheckResponse allowedResp = scoringService.scoreTransaction(req);
        assertThat(allowedResp.action()).isEqualTo(FraudAction.ALLOW.name());
        assertThat(allowedResp.score()).isZero();
        assertThat(allowedResp.rulesTriggered()).contains("ALLOWLIST_BYPASS");

        when(allowlistRepository.findActiveAllowlistEntry("USER", userId.toString()))
            .thenReturn(Optional.empty());
        when(ruleRepo.findActiveOrderByPriority()).thenReturn(List.of());

        FraudCheckResponse comboResp = scoringService.scoreTransaction(req);
        assertThat(comboResp.score()).isGreaterThanOrEqualTo(76);
        assertThat(comboResp.action()).isEqualTo(FraudAction.BLOCK.name());
        assertThat(comboResp.rulesTriggered()).contains("HIGH_RISK_PAYMENT_NEW_DEVICE");

        FraudRule allowRule = new FraudRule();
        allowRule.setName("VIP_ALLOW");
        allowRule.setRuleType("AMOUNT");
        allowRule.setConditionJson(Map.of("maxAmountCents", 10_000L));
        allowRule.setScoreImpact(25);
        allowRule.setAction("ALLOW");

        FraudRule blockRule = new FraudRule();
        blockRule.setName("HARD_BLOCK_AMOUNT");
        blockRule.setRuleType("AMOUNT");
        blockRule.setConditionJson(Map.of("maxAmountCents", 100_000L));
        blockRule.setScoreImpact(90);
        blockRule.setAction("BLOCK");

        when(ruleRepo.findActiveOrderByPriority()).thenReturn(List.of(allowRule));
        FraudCheckResponse allowRuleOnly = scoringService.scoreTransaction(req);
        assertThat(allowRuleOnly.action()).isEqualTo(FraudAction.ALLOW.name());
        assertThat(allowRuleOnly.score()).isZero();

        when(ruleRepo.findActiveOrderByPriority()).thenReturn(List.of(allowRule, blockRule));
        FraudCheckResponse blockOverridesAllow = scoringService.scoreTransaction(req);
        assertThat(blockOverridesAllow.action()).isEqualTo(FraudAction.BLOCK.name());

        assertThatThrownBy(() -> allowlistService.allowEntity(
            new BlockRequest("USER", userId.toString(), "expired", Instant.parse("2026-11-19T11:00:00Z")), "admin"))
            .isInstanceOf(ApiException.class);
    }

    @Test
    void reAllowlistingExpiredEntryDeactivatesOldRowAndFlushesBeforeInsert() {
        AllowlistRepository allowlistRepository = mock(AllowlistRepository.class);
        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-19T12:00:00Z"), ZoneOffset.UTC);
        AllowlistService allowlistService = new AllowlistService(allowlistRepository, fixedClock);

        AllowlistedEntity expiredActive = new AllowlistedEntity();
        expiredActive.setEntityType("USER");
        expiredActive.setEntityValue("user-expired");
        expiredActive.setActive(true);
        expiredActive.setExpiresAt(Instant.parse("2026-11-19T10:00:00Z"));

        when(allowlistRepository.findActiveAllowlistEntry("USER", "user-expired"))
            .thenReturn(Optional.of(expiredActive));
        when(allowlistRepository.saveAndFlush(any(AllowlistedEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(allowlistRepository.save(any(AllowlistedEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(allowlistService.isAllowlisted("USER", "user-expired")).isFalse();

        Instant newExpiry = Instant.parse("2026-11-20T12:00:00Z");
        AllowlistedEntity saved = allowlistService.allowEntity(
            new BlockRequest("user", "user-expired", "renewed trust", newExpiry), "admin-2");

        assertThat(expiredActive.isActive()).isFalse();
        verify(allowlistRepository).saveAndFlush(expiredActive);
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getExpiresAt()).isEqualTo(newExpiry);
        assertThat(saved.getAllowedBy()).isEqualTo("admin-2");
    }
}
