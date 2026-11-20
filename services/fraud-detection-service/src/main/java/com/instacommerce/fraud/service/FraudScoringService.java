package com.instacommerce.fraud.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.fraud.config.FraudOperationalMode;
import com.instacommerce.fraud.domain.model.FraudAction;
import com.instacommerce.fraud.domain.model.FraudRule;
import com.instacommerce.fraud.domain.model.FraudSignal;
import com.instacommerce.fraud.domain.model.RiskLevel;
import com.instacommerce.fraud.dto.request.FraudCheckRequest;
import com.instacommerce.fraud.dto.request.FraudReportRequest;
import com.instacommerce.fraud.dto.response.FraudCheckResponse;
import com.instacommerce.fraud.repository.FraudRuleRepository;
import com.instacommerce.fraud.repository.FraudSignalRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class FraudScoringService {

    private static final Logger log = LoggerFactory.getLogger(FraudScoringService.class);
    private static final String SCORING_ERROR_METRIC = "fraud.scoring.errors";

    private final FraudRuleRepository fraudRuleRepository;
    private final FraudSignalRepository fraudSignalRepository;
    private final RuleEvaluationService ruleEvaluationService;
    private final BlocklistService blocklistService;
    private final OutboxService outboxService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final FraudOperationalMode operationalMode;
    private final TransactionTemplate persistenceTxTemplate;
    private AllowlistService allowlistService;

    @Autowired(required = false)
    public void setAllowlistService(AllowlistService allowlistService) {
        this.allowlistService = allowlistService;
    }

    @Autowired
    public FraudScoringService(FraudRuleRepository fraudRuleRepository,
                               FraudSignalRepository fraudSignalRepository,
                               RuleEvaluationService ruleEvaluationService,
                               BlocklistService blocklistService,
                               OutboxService outboxService,
                               ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                               ObjectProvider<ObjectMapper> objectMapperProvider,
                               ObjectProvider<MeterRegistry> meterRegistryProvider,
                               ObjectProvider<PlatformTransactionManager> transactionManagerProvider,
                               @Value("${fraud.operational-mode:AUTO_BLOCK}") String operationalModeStr) {
        this(
                fraudRuleRepository,
                fraudSignalRepository,
                ruleEvaluationService,
                blocklistService,
                outboxService,
                redisTemplateProvider != null ? redisTemplateProvider.getIfAvailable() : null,
                objectMapperProvider != null ? objectMapperProvider.getIfAvailable(ObjectMapper::new) : new ObjectMapper(),
                meterRegistryProvider != null ? meterRegistryProvider.getIfAvailable(SimpleMeterRegistry::new) : new SimpleMeterRegistry(),
                transactionManagerProvider != null ? transactionManagerProvider.getIfAvailable() : null,
                parseOperationalMode(operationalModeStr)
        );
    }

    public FraudScoringService(FraudRuleRepository fraudRuleRepository,
                               FraudSignalRepository fraudSignalRepository,
                               RuleEvaluationService ruleEvaluationService,
                               BlocklistService blocklistService,
                               OutboxService outboxService,
                               ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                               ObjectProvider<ObjectMapper> objectMapperProvider,
                               ObjectProvider<MeterRegistry> meterRegistryProvider,
                               @Value("${fraud.operational-mode:AUTO_BLOCK}") String operationalModeStr) {
        this(
                fraudRuleRepository,
                fraudSignalRepository,
                ruleEvaluationService,
                blocklistService,
                outboxService,
                redisTemplateProvider,
                objectMapperProvider,
                meterRegistryProvider,
                null,
                operationalModeStr
        );
    }

    public FraudScoringService(FraudRuleRepository fraudRuleRepository,
                               FraudSignalRepository fraudSignalRepository,
                               RuleEvaluationService ruleEvaluationService,
                               BlocklistService blocklistService,
                               OutboxService outboxService,
                               ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                               ObjectProvider<ObjectMapper> objectMapperProvider) {
        this(fraudRuleRepository, fraudSignalRepository, ruleEvaluationService, blocklistService,
                outboxService, redisTemplateProvider, objectMapperProvider, null,
                FraudOperationalMode.AUTO_BLOCK.name());
    }

    public FraudScoringService(FraudRuleRepository fraudRuleRepository,
                               FraudSignalRepository fraudSignalRepository,
                               RuleEvaluationService ruleEvaluationService,
                               BlocklistService blocklistService,
                               OutboxService outboxService,
                               StringRedisTemplate redisTemplate) {
        this(fraudRuleRepository, fraudSignalRepository, ruleEvaluationService, blocklistService,
                outboxService, redisTemplate, new SimpleMeterRegistry(), FraudOperationalMode.AUTO_BLOCK);
    }

    public FraudScoringService(FraudRuleRepository fraudRuleRepository,
                               FraudSignalRepository fraudSignalRepository,
                               RuleEvaluationService ruleEvaluationService,
                               BlocklistService blocklistService,
                               OutboxService outboxService) {
        this(fraudRuleRepository, fraudSignalRepository, ruleEvaluationService, blocklistService,
                outboxService, (StringRedisTemplate) null);
    }

    FraudScoringService(FraudRuleRepository fraudRuleRepository,
                        FraudSignalRepository fraudSignalRepository,
                        RuleEvaluationService ruleEvaluationService,
                        BlocklistService blocklistService,
                        OutboxService outboxService,
                        StringRedisTemplate redisTemplate,
                        MeterRegistry meterRegistry,
                        FraudOperationalMode operationalMode) {
        this(fraudRuleRepository, fraudSignalRepository, ruleEvaluationService, blocklistService,
                outboxService, redisTemplate, new ObjectMapper(), meterRegistry, null, operationalMode);
    }

    FraudScoringService(FraudRuleRepository fraudRuleRepository,
                        FraudSignalRepository fraudSignalRepository,
                        RuleEvaluationService ruleEvaluationService,
                        BlocklistService blocklistService,
                        OutboxService outboxService,
                        StringRedisTemplate redisTemplate,
                        MeterRegistry meterRegistry,
                        PlatformTransactionManager transactionManager,
                        FraudOperationalMode operationalMode) {
        this(fraudRuleRepository, fraudSignalRepository, ruleEvaluationService, blocklistService,
                outboxService, redisTemplate, new ObjectMapper(), meterRegistry, transactionManager, operationalMode);
    }

    private FraudScoringService(FraudRuleRepository fraudRuleRepository,
                                FraudSignalRepository fraudSignalRepository,
                                RuleEvaluationService ruleEvaluationService,
                                BlocklistService blocklistService,
                                OutboxService outboxService,
                                StringRedisTemplate redisTemplate,
                                ObjectMapper objectMapper,
                                MeterRegistry meterRegistry,
                                PlatformTransactionManager transactionManager,
                                FraudOperationalMode operationalMode) {
        this.fraudRuleRepository = fraudRuleRepository;
        this.fraudSignalRepository = fraudSignalRepository;
        this.ruleEvaluationService = ruleEvaluationService;
        this.blocklistService = blocklistService;
        this.outboxService = outboxService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.meterRegistry = meterRegistry != null ? meterRegistry : new SimpleMeterRegistry();
        this.operationalMode = operationalMode != null ? operationalMode : FraudOperationalMode.AUTO_BLOCK;
        this.persistenceTxTemplate = createRequiresNewTemplate(transactionManager);
    }

    private static TransactionTemplate createRequiresNewTemplate(PlatformTransactionManager transactionManager) {
        if (transactionManager == null) {
            return null;
        }
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private static FraudOperationalMode parseOperationalMode(String operationalModeStr) {
        if (operationalModeStr == null || operationalModeStr.isBlank()) {
            return FraudOperationalMode.AUTO_BLOCK;
        }
        try {
            return FraudOperationalMode.valueOf(operationalModeStr.trim());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid fraud.operational-mode='{}', failing closed to AUTO_BLOCK", operationalModeStr);
            return FraudOperationalMode.AUTO_BLOCK;
        }
    }

    public FraudCheckResponse scoreTransaction(FraudCheckRequest request) {
        FraudOperationalMode mode = this.operationalMode;
        if (mode == FraudOperationalMode.PASS_THROUGH) {
            log.warn("fraud.pass_through_mode active -- allowing transaction without scoring");
            return allowResponse();
        }

        try {
            if (isAnyEntityBlocked(request)) {
                if (mode == FraudOperationalMode.SHADOW) {
                    log.info("fraud.shadow_mode blocklist_hit order={}", request.orderId());
                    return allowResponse();
                }
                FraudAction blockAction = mode == FraudOperationalMode.MANUAL_REVIEW
                        ? FraudAction.REVIEW
                        : FraudAction.BLOCK;
                return persistAndRespond(request, 100, RiskLevel.CRITICAL, blockAction,
                        List.of("BLOCKLIST_HIT"));
            }
        } catch (Exception ex) {
            log.error("Blocklist evaluation failed for order={}", request.orderId(), ex);
            return failClosedOnDependencyError(request, mode, "blocklist",
                    RiskLevel.CRITICAL, FraudAction.BLOCK, "BLOCKLIST_EVALUATION_ERROR");
        }

        if (isAnyEntityAllowlisted(request)) {
            return persistAndRespond(request, 0, RiskLevel.LOW, FraudAction.ALLOW,
                    List.of("ALLOWLIST_BYPASS"));
        }

        Double cachedFraudScore = getCachedFraudRiskScore(request.userId(), mode);
        if (cachedFraudScore != null && cachedFraudScore > 0.85) {
            if (mode == FraudOperationalMode.SHADOW) {
                log.info("fraud.shadow_mode high_cached_score={} order={}", cachedFraudScore, request.orderId());
                return allowResponse();
            }
            FraudAction scoreAction = mode == FraudOperationalMode.MANUAL_REVIEW
                    ? FraudAction.REVIEW
                    : FraudAction.BLOCK;
            log.warn("Flagging checkout ({}): customer fraud risk score {} exceeds threshold 0.85 for user {}",
                    scoreAction, cachedFraudScore, request.userId());
            int score = (int) Math.round(cachedFraudScore * 100);
            return persistAndRespond(request, Math.min(100, Math.max(0, score)),
                    RiskLevel.CRITICAL, scoreAction, List.of("HIGH_FRAUD_RISK_SCORE"));
        }

        List<FraudRule> rules;
        try {
            rules = loadActiveRules();
            if (rules == null) {
                throw new IllegalStateException("Active fraud rule lookup returned null");
            }
        } catch (Exception ex) {
            log.error("Failed to load active fraud rules for order={}", request.orderId(), ex);
            return failClosedOnDependencyError(request, mode, "rule_load",
                    RiskLevel.CRITICAL, FraudAction.BLOCK, "RULE_LOAD_ERROR");
        }

        int totalScore = 0;
        int minimumErrorScore = 0;
        FraudAction highestAction = FraudAction.ALLOW;
        List<String> triggeredRules = new ArrayList<>();
        boolean allowRuleMatched = false;
        boolean nonAllowRuleMatched = false;

        for (FraudRule rule : rules) {
            try {
                if (ruleEvaluationService.evaluateRule(rule, request)) {
                    FraudAction ruleAction = FraudAction.valueOf(rule.getAction());
                    triggeredRules.add(rule.getName());
                    if (ruleAction == FraudAction.ALLOW) {
                        allowRuleMatched = true;
                        totalScore = Math.max(0, totalScore - Math.abs(rule.getScoreImpact()));
                    } else {
                        nonAllowRuleMatched = true;
                        totalScore += rule.getScoreImpact();
                        highestAction = FraudAction.escalate(highestAction, ruleAction);
                    }
                }
            } catch (Exception ex) {
                FraudAction ruleFallback = resolveRuleFailureAction(rule);
                FraudAction effectiveFallback = resolveOperationalFailureAction(mode, ruleFallback);
                recordErrorMetric("rule_evaluation", mode, effectiveFallback.name());
                String ruleName = rule != null && rule.getName() != null ? rule.getName() : "UNKNOWN_RULE";
                log.error("Rule evaluation failed for {} ({}), applying fail-closed fallback={}",
                        ruleName, rule != null ? rule.getId() : null, effectiveFallback, ex);
                triggeredRules.add("RULE_EVALUATION_ERROR:" + ruleName);
                highestAction = FraudAction.escalate(highestAction, ruleFallback);
                int errorFloor = ruleFallback == FraudAction.BLOCK
                        ? RiskLevel.CRITICAL.getMinScore()
                        : RiskLevel.HIGH.getMinScore();
                minimumErrorScore = Math.max(minimumErrorScore, errorFloor);
            }
        }

        if (!allowRuleMatched) {
            int comboScore = evaluateHighRiskPaymentAndNewDeviceCombo(request);
            if (comboScore > 0 && !triggeredRules.contains("HIGH_RISK_PAYMENT_NEW_DEVICE")) {
                totalScore += comboScore;
                triggeredRules.add("HIGH_RISK_PAYMENT_NEW_DEVICE");
                nonAllowRuleMatched = true;
            }
        }

        if (allowRuleMatched && !nonAllowRuleMatched && minimumErrorScore == 0) {
            totalScore = 0;
            highestAction = FraudAction.ALLOW;
        }

        int clampedScore = Math.min(100, Math.max(minimumErrorScore, Math.max(0, totalScore)));
        RiskLevel riskLevel = RiskLevel.fromScore(clampedScore);
        FraudAction finalAction = FraudAction.escalate(
                FraudAction.fromRiskLevel(riskLevel), highestAction);

        if (mode == FraudOperationalMode.SHADOW) {
            log.info("fraud.shadow_mode score={} -- would have {}", clampedScore,
                    finalAction != FraudAction.ALLOW ? finalAction : "ALLOWED");
            return allowResponse();
        }
        FraudAction responseAction = mode == FraudOperationalMode.MANUAL_REVIEW
                ? FraudAction.REVIEW
                : finalAction;
        if (mode == FraudOperationalMode.MANUAL_REVIEW) {
            log.info("fraud.manual_review_mode score={}", clampedScore);
        }
        return minimumErrorScore > 0
                ? persistFailClosedSafe(request, mode, clampedScore, riskLevel, responseAction, triggeredRules)
                : persistAndRespond(request, clampedScore, riskLevel, responseAction, triggeredRules);
    }

    @Cacheable(value = "fraudRules")
    public List<FraudRule> loadActiveRules() {
        return fraudRuleRepository.findActiveOrderByPriority();
    }

    public Double getCachedFraudRiskScore(UUID userId) {
        return getCachedFraudRiskScore(userId, this.operationalMode);
    }

    private Double getCachedFraudRiskScore(UUID userId, FraudOperationalMode mode) {
        if (redisTemplate == null || userId == null) {
            return null;
        }
        try {
            String key = "fraud:score:" + userId;
            String rawVal = redisTemplate.opsForValue().get(key);
            if (rawVal == null || rawVal.isBlank()) {
                return null;
            }
            if (rawVal.trim().startsWith("{")) {
                JsonNode node = objectMapper.readTree(rawVal);
                if (node.has("fraud_risk_score")) {
                    return node.get("fraud_risk_score").asDouble();
                }
            } else {
                return Double.parseDouble(rawVal.trim());
            }
        } catch (Exception e) {
            recordErrorMetric("redis_cache", mode, "FALLBACK_RULES");
            log.warn("Failed reading cached fraud risk score for userId {}: {}", userId, e.getMessage());
        }
        return null;
    }

    private FraudCheckResponse failClosedOnDependencyError(FraudCheckRequest request,
                                                             FraudOperationalMode mode,
                                                             String stage,
                                                             RiskLevel activeRiskLevel,
                                                             FraudAction activeFallbackAction,
                                                             String failureSignalTag) {
        FraudAction effectiveAction = resolveOperationalFailureAction(mode, activeFallbackAction);
        recordErrorMetric(stage, mode, effectiveAction.name());
        if (mode == FraudOperationalMode.SHADOW) {
            return allowResponse();
        }
        RiskLevel effectiveRisk = mode == FraudOperationalMode.MANUAL_REVIEW
                ? RiskLevel.HIGH
                : activeRiskLevel;
        int score = effectiveAction == FraudAction.BLOCK ? 100 : RiskLevel.HIGH.getMinScore();
        return persistFailClosedSafe(request, mode, score, effectiveRisk, effectiveAction,
                List.of(failureSignalTag));
    }

    private FraudCheckResponse persistFailClosedSafe(FraudCheckRequest request,
                                                       FraudOperationalMode mode,
                                                       int score,
                                                       RiskLevel riskLevel,
                                                       FraudAction action,
                                                       List<String> triggeredRules) {
        try {
            return persistAndRespond(request, score, riskLevel, action, triggeredRules);
        } catch (Exception ex) {
            recordErrorMetric("signal_persist", mode, action.name());
            log.error("Failed persisting fail-closed fraud signal for order={}, returning fail-closed action={}",
                    request.orderId(), action, ex);
            return new FraudCheckResponse(score, riskLevel.name(), action.name(), triggeredRules, null);
        }
    }

    private FraudAction resolveRuleFailureAction(FraudRule rule) {
        if (rule == null || rule.getAction() == null) {
            return FraudAction.REVIEW;
        }
        try {
            FraudAction parsed = FraudAction.valueOf(rule.getAction());
            return parsed == FraudAction.BLOCK ? FraudAction.BLOCK : FraudAction.REVIEW;
        } catch (IllegalArgumentException ex) {
            return FraudAction.REVIEW;
        }
    }

    private FraudAction resolveOperationalFailureAction(FraudOperationalMode mode, FraudAction activeFallback) {
        return switch (mode) {
            case SHADOW, PASS_THROUGH -> FraudAction.ALLOW;
            case MANUAL_REVIEW -> FraudAction.REVIEW;
            case AUTO_BLOCK -> activeFallback;
        };
    }

    private void recordErrorMetric(String stage, FraudOperationalMode mode, String fallbackAction) {
        meterRegistry.counter(SCORING_ERROR_METRIC,
                "stage", stage,
                "mode", mode.name(),
                "fallback_action", fallbackAction).increment();
    }

    private static FraudCheckResponse allowResponse() {
        return new FraudCheckResponse(0, RiskLevel.LOW.name(), FraudAction.ALLOW.name(), List.of(), null);
    }

    private boolean isAnyEntityBlocked(FraudCheckRequest request) {
        if (blocklistService.isBlocked("USER", request.userId().toString())) {
            return true;
        }
        if (request.deviceFingerprint() != null &&
                blocklistService.isBlocked("DEVICE", request.deviceFingerprint())) {
            return true;
        }
        if (request.ipAddress() != null &&
                blocklistService.isBlocked("IP", request.ipAddress())) {
            return true;
        }
        return false;
    }

    private boolean isAnyEntityAllowlisted(FraudCheckRequest request) {
        if (allowlistService == null) {
            return false;
        }
        if (request.userId() != null && allowlistService.isAllowlisted("USER", request.userId().toString())) {
            return true;
        }
        if (request.deviceFingerprint() != null
                && !request.deviceFingerprint().isBlank()
                && allowlistService.isAllowlisted("DEVICE", request.deviceFingerprint())) {
            return true;
        }
        if (request.ipAddress() != null
                && !request.ipAddress().isBlank()
                && allowlistService.isAllowlisted("IP", request.ipAddress())) {
            return true;
        }
        return false;
    }

    private int evaluateHighRiskPaymentAndNewDeviceCombo(FraudCheckRequest request) {
        String method = request.paymentMethodType();
        if (method == null || method.isBlank()) {
            return 0;
        }
        boolean missingDevice = request.deviceFingerprint() == null || request.deviceFingerprint().isBlank();
        boolean newDeviceContext = missingDevice || request.isNewUser();
        if (!newDeviceContext) {
            return 0;
        }
        String normalized = method.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "CRYPTO", "GIFT_CARD", "PREPAID_CARD", "INTERNATIONAL_CARD" ->
                (missingDevice || request.totalCents() >= 100_000L) ? 80 : 55;
            case "COD" ->
                request.totalCents() >= 100_000L ? (missingDevice ? 60 : 35) : 0;
            default -> 0;
        };
    }

    private FraudCheckResponse persistAndRespond(FraudCheckRequest request, int score,
                                                 RiskLevel riskLevel, FraudAction action,
                                                 List<String> triggeredRules) {
        FraudSignal saved = persistenceTxTemplate != null
                ? persistenceTxTemplate.execute(status -> saveSignalAndPublishOutbox(request, score, riskLevel, action, triggeredRules))
                : saveSignalAndPublishOutbox(request, score, riskLevel, action, triggeredRules);

        log.info("Fraud score for order={}: score={}, risk={}, action={}, rules={}",
                request.orderId(), score, riskLevel, action, triggeredRules);

        return new FraudCheckResponse(score, riskLevel.name(), action.name(),
                triggeredRules, saved != null ? saved.getId() : null);
    }

    private FraudSignal saveSignalAndPublishOutbox(FraudCheckRequest request, int score,
                                                   RiskLevel riskLevel, FraudAction action,
                                                   List<String> triggeredRules) {
        FraudSignal signal = new FraudSignal();
        signal.setUserId(request.userId());
        signal.setOrderId(request.orderId());
        signal.setDeviceFingerprint(request.deviceFingerprint());
        signal.setIpAddress(request.ipAddress());
        signal.setScore(score);
        signal.setRiskLevel(riskLevel.name());
        signal.setRulesTriggered(triggeredRules);
        signal.setActionTaken(action.name());

        FraudSignal saved = fraudSignalRepository.save(signal);

        if (action != FraudAction.ALLOW) {
            outboxService.publish("Fraud", saved.getId().toString(), "FraudDetected",
                    Map.of(
                            "signalId", saved.getId(),
                            "userId", request.userId(),
                            "orderId", request.orderId(),
                            "score", score,
                            "riskLevel", riskLevel.name(),
                            "action", action.name(),
                            "rulesTriggered", triggeredRules
                    ));
        }

        return saved;
    }

    public Map<String, String> reportFraud(FraudReportRequest request) {
        FraudSignal signal = new FraudSignal();
        signal.setUserId(request.userId());
        signal.setOrderId(request.orderId() != null ? request.orderId() : UUID.randomUUID());
        signal.setDeviceFingerprint(request.deviceFingerprint());
        signal.setIpAddress(request.ipAddress());
        signal.setScore(RiskLevel.HIGH.getMinScore());
        signal.setRiskLevel(RiskLevel.HIGH.name());
        String ruleTag = (request.reason() != null && !request.reason().isBlank())
                ? "USER_REPORT:" + request.reason()
                : "USER_REPORT";
        signal.setRulesTriggered(List.of(ruleTag));
        signal.setActionTaken(FraudAction.FLAG.name());

        FraudSignal saved = persistenceTxTemplate != null
                ? persistenceTxTemplate.execute(status -> fraudSignalRepository.save(signal))
                : fraudSignalRepository.save(signal);
        return Map.of(
                "status", "reported",
                "signalId", saved != null && saved.getId() != null ? saved.getId().toString() : ""
        );
    }
}
