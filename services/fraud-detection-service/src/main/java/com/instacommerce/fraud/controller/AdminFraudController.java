package com.instacommerce.fraud.controller;

import com.instacommerce.fraud.domain.model.BlockedEntity;
import com.instacommerce.fraud.domain.model.FraudRule;
import com.instacommerce.fraud.dto.request.BlockRequest;
import com.instacommerce.fraud.dto.request.FraudRuleRequest;
import com.instacommerce.fraud.exception.FraudRuleNotFoundException;
import com.instacommerce.fraud.repository.FraudRuleRepository;
import com.instacommerce.fraud.service.BlocklistService;
import com.instacommerce.fraud.service.RuleConditionValidator;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/fraud")
public class AdminFraudController {

    private final FraudRuleRepository fraudRuleRepository;
    private final BlocklistService blocklistService;
    private final RuleConditionValidator ruleConditionValidator;

    public AdminFraudController(FraudRuleRepository fraudRuleRepository,
                                BlocklistService blocklistService,
                                RuleConditionValidator ruleConditionValidator) {
        this.fraudRuleRepository = fraudRuleRepository;
        this.blocklistService = blocklistService;
        this.ruleConditionValidator = ruleConditionValidator;
    }

    // --- Rules CRUD ---

    @GetMapping("/rules")
    public Page<FraudRule> listRules(Pageable pageable) {
        return fraudRuleRepository.findAll(pageable);
    }

    @GetMapping("/rules/{id}")
    public FraudRule getRule(@PathVariable UUID id) {
        return fraudRuleRepository.findById(id)
                .orElseThrow(() -> new FraudRuleNotFoundException(id));
    }

    @PostMapping("/rules")
    @ResponseStatus(HttpStatus.CREATED)
    @CacheEvict(value = "fraudRules", allEntries = true)
    public FraudRule createRule(@Valid @RequestBody FraudRuleRequest request) {
        ruleConditionValidator.validate(request.ruleType(), request.conditionJson());
        FraudRule rule = new FraudRule();
        rule.setName(request.name());
        rule.setRuleType(request.ruleType());
        rule.setConditionJson(request.conditionJson());
        rule.setScoreImpact(request.scoreImpact());
        rule.setAction(request.action() != null ? request.action() : "FLAG");
        rule.setActive(request.active());
        rule.setPriority(request.priority());
        return fraudRuleRepository.save(rule);
    }

    @PutMapping("/rules/{id}")
    @CacheEvict(value = "fraudRules", allEntries = true)
    public FraudRule updateRule(@PathVariable UUID id, @Valid @RequestBody FraudRuleRequest request) {
        ruleConditionValidator.validate(request.ruleType(), request.conditionJson());
        FraudRule rule = fraudRuleRepository.findById(id)
                .orElseThrow(() -> new FraudRuleNotFoundException(id));
        rule.setName(request.name());
        rule.setRuleType(request.ruleType());
        rule.setConditionJson(request.conditionJson());
        rule.setScoreImpact(request.scoreImpact());
        rule.setAction(request.action() != null ? request.action() : "FLAG");
        rule.setActive(request.active());
        rule.setPriority(request.priority());
        return fraudRuleRepository.save(rule);
    }

    @DeleteMapping("/rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @CacheEvict(value = "fraudRules", allEntries = true)
    public void deleteRule(@PathVariable UUID id) {
        if (!fraudRuleRepository.existsById(id)) {
            throw new FraudRuleNotFoundException(id);
        }
        fraudRuleRepository.deleteById(id);
    }

    // --- Blocklist ---

    @GetMapping("/blocklist")
    public Page<BlockedEntity> listBlockedEntities(Pageable pageable) {
        return blocklistService.listActive(pageable);
    }

    @PostMapping("/blocklist")
    @ResponseStatus(HttpStatus.CREATED)
    public BlockedEntity blockEntity(@Valid @RequestBody BlockRequest request) {
        return blocklistService.block(request.entityType(), request.entityValue(),
                request.reason(), request.expiresAt(), "admin");
    }

    @DeleteMapping("/blocklist/{entityType}/{entityValue}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unblockEntity(@PathVariable String entityType, @PathVariable String entityValue) {
        blocklistService.unblock(entityType, entityValue);
    }
}
