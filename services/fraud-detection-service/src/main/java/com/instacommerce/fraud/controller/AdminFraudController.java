package com.instacommerce.fraud.controller;

import com.instacommerce.fraud.domain.model.AllowlistedEntity;
import com.instacommerce.fraud.domain.model.BlockedEntity;
import com.instacommerce.fraud.domain.model.FraudRule;
import com.instacommerce.fraud.dto.request.BlockRequest;
import com.instacommerce.fraud.dto.request.FraudRuleRequest;
import com.instacommerce.fraud.exception.FraudRuleNotFoundException;
import com.instacommerce.fraud.repository.FraudRuleRepository;
import com.instacommerce.fraud.service.AllowlistService;
import com.instacommerce.fraud.service.BlocklistService;
import com.instacommerce.fraud.service.RuleConditionValidator;
import com.instacommerce.platform.web.PageableUtils;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
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
@RequestMapping({"/admin/fraud", "/api/v1/admin/fraud"})
public class AdminFraudController {

    private final FraudRuleRepository fraudRuleRepository;
    private final BlocklistService blocklistService;
    private final RuleConditionValidator ruleConditionValidator;
    private final AllowlistService allowlistService;

    public AdminFraudController(FraudRuleRepository fraudRuleRepository,
                                BlocklistService blocklistService,
                                RuleConditionValidator ruleConditionValidator) {
        this(fraudRuleRepository, blocklistService, ruleConditionValidator, (AllowlistService) null);
    }

    @Autowired
    public AdminFraudController(FraudRuleRepository fraudRuleRepository,
                                BlocklistService blocklistService,
                                RuleConditionValidator ruleConditionValidator,
                                ObjectProvider<AllowlistService> allowlistServiceProvider) {
        this(fraudRuleRepository, blocklistService, ruleConditionValidator,
                allowlistServiceProvider != null ? allowlistServiceProvider.getIfAvailable() : null);
    }

    public AdminFraudController(FraudRuleRepository fraudRuleRepository,
                                BlocklistService blocklistService,
                                RuleConditionValidator ruleConditionValidator,
                                AllowlistService allowlistService) {
        this.fraudRuleRepository = fraudRuleRepository;
        this.blocklistService = blocklistService;
        this.ruleConditionValidator = ruleConditionValidator;
        this.allowlistService = allowlistService;
    }

    // --- Rules CRUD ---

    @GetMapping("/rules")
    public Page<FraudRule> listRules(Pageable pageable) {
        return fraudRuleRepository.findAll(PageableUtils.sanitize(pageable));
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
        return blocklistService.listActive(PageableUtils.sanitize(pageable));
    }

    @PostMapping("/blocklist")
    @ResponseStatus(HttpStatus.CREATED)
    public BlockedEntity blockEntity(@Valid @RequestBody BlockRequest request, Authentication authentication) {
        return blocklistService.block(request.entityType(), request.entityValue(),
                request.reason(), request.expiresAt(), resolveActor(authentication));
    }

    @DeleteMapping("/blocklist/{entityType}/{entityValue}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unblockEntity(@PathVariable String entityType, @PathVariable String entityValue) {
        blocklistService.unblock(entityType, entityValue);
    }

    // --- Allowlist ---

    @GetMapping("/allowlist")
    public List<AllowlistedEntity> listAllowlistedEntities() {
        return allowlistService != null ? allowlistService.listActive() : List.of();
    }

    @PostMapping("/allowlist")
    @ResponseStatus(HttpStatus.CREATED)
    public AllowlistedEntity allowEntity(@Valid @RequestBody BlockRequest request, Authentication authentication) {
        if (allowlistService == null) {
            throw new IllegalStateException("AllowlistService is not available");
        }
        return allowlistService.allowEntity(request, resolveActor(authentication));
    }

    @DeleteMapping("/allowlist/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeAllowlist(@PathVariable UUID id) {
        if (allowlistService == null) {
            throw new IllegalStateException("AllowlistService is not available");
        }
        allowlistService.revokeAllowlist(id);
    }

    private static String resolveActor(Authentication authentication) {
        if (authentication != null && authentication.getName() != null && !authentication.getName().isBlank()) {
            return authentication.getName();
        }
        return "admin";
    }
}
