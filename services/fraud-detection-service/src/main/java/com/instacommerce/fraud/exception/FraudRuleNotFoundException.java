package com.instacommerce.fraud.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class FraudRuleNotFoundException extends ApiException {

    public FraudRuleNotFoundException(UUID ruleId) {
        super(HttpStatus.NOT_FOUND, "FRAUD_RULE_NOT_FOUND", "Fraud rule not found: " + ruleId);
    }
}
