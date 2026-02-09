package com.instacommerce.catalog.pricing;

import com.instacommerce.catalog.domain.model.PricingRule;
import com.instacommerce.catalog.repository.PricingRuleRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(2)
public class ZoneOverrideStrategy implements PricingStrategy {
    private final PricingRuleRepository pricingRuleRepository;

    public ZoneOverrideStrategy(PricingRuleRepository pricingRuleRepository) {
        this.pricingRuleRepository = pricingRuleRepository;
    }

    @Override
    public PricingResult apply(PricingContext context, long currentPriceCents) {
        List<PricingRule> rules = pricingRuleRepository.findApplicable(
            context.product().getId(),
            context.storeId(),
            context.zoneId(),
            Instant.now());
        if (rules.isEmpty()) {
            return PricingResult.unchanged(currentPriceCents);
        }
        PricingRule rule = rules.getFirst();
        String ruleLabel = context.storeId() != null ? context.storeId() : context.zoneId();
        if (ruleLabel == null) {
            ruleLabel = "default";
        }
        return new PricingResult(rule.getOverridePriceCents(), "ZONE_OVERRIDE:" + ruleLabel);
    }

    @Override
    public int order() {
        return 2;
    }
}
