package com.instacommerce.fraud.service;

import com.instacommerce.fraud.domain.model.FraudAction;
import com.instacommerce.fraud.domain.model.FraudRule;
import com.instacommerce.fraud.domain.model.FraudSignal;
import com.instacommerce.fraud.domain.model.RiskLevel;
import com.instacommerce.fraud.dto.request.FraudCheckRequest;
import com.instacommerce.fraud.dto.response.FraudCheckResponse;
import com.instacommerce.fraud.repository.FraudRuleRepository;
import com.instacommerce.fraud.repository.FraudSignalRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FraudScoringService {

    private static final Logger log = LoggerFactory.getLogger(FraudScoringService.class);

    private final FraudRuleRepository fraudRuleRepository;
    private final FraudSignalRepository fraudSignalRepository;
    private final RuleEvaluationService ruleEvaluationService;
    private final BlocklistService blocklistService;
    private final OutboxService outboxService;

    public FraudScoringService(FraudRuleRepository fraudRuleRepository,
                               FraudSignalRepository fraudSignalRepository,
                               RuleEvaluationService ruleEvaluationService,
                               BlocklistService blocklistService,
                               OutboxService outboxService) {
        this.fraudRuleRepository = fraudRuleRepository;
        this.fraudSignalRepository = fraudSignalRepository;
        this.ruleEvaluationService = ruleEvaluationService;
        this.blocklistService = blocklistService;
        this.outboxService = outboxService;
    }

    @Transactional
    public FraudCheckResponse scoreTransaction(FraudCheckRequest request) {
        // Fast-path: check blocklists first
        if (isAnyEntityBlocked(request)) {
            return persistAndRespond(request, 100, RiskLevel.CRITICAL, FraudAction.BLOCK,
                    List.of("BLOCKLIST_HIT"));
        }

        List<FraudRule> rules = loadActiveRules();
        int totalScore = 0;
        FraudAction highestAction = FraudAction.ALLOW;
        List<String> triggeredRules = new ArrayList<>();

        for (FraudRule rule : rules) {
            if (ruleEvaluationService.evaluateRule(rule, request)) {
                totalScore += rule.getScoreImpact();
                triggeredRules.add(rule.getName());
                FraudAction ruleAction = FraudAction.valueOf(rule.getAction());
                highestAction = FraudAction.escalate(highestAction, ruleAction);
            }
        }

        int clampedScore = Math.min(100, Math.max(0, totalScore));
        RiskLevel riskLevel = RiskLevel.fromScore(clampedScore);
        FraudAction finalAction = FraudAction.escalate(
                FraudAction.fromRiskLevel(riskLevel), highestAction);

        return persistAndRespond(request, clampedScore, riskLevel, finalAction, triggeredRules);
    }

    @Cacheable(value = "fraudRules")
    public List<FraudRule> loadActiveRules() {
        return fraudRuleRepository.findActiveOrderByPriority();
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

    private FraudCheckResponse persistAndRespond(FraudCheckRequest request, int score,
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
            outboxService.publish("FraudSignal", saved.getId().toString(), "FraudDetected",
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

        log.info("Fraud score for order={}: score={}, risk={}, action={}, rules={}",
                request.orderId(), score, riskLevel, action, triggeredRules);

        return new FraudCheckResponse(score, riskLevel.name(), action.name(),
                triggeredRules, saved.getId());
    }
}
