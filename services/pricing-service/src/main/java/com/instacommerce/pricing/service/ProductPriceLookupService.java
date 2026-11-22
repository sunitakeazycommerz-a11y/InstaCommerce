package com.instacommerce.pricing.service;

import com.instacommerce.pricing.domain.PriceRule;
import com.instacommerce.pricing.exception.PriceRuleNotFoundException;
import com.instacommerce.pricing.repository.PriceRuleRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductPriceLookupService {

    private final PriceRuleRepository priceRuleRepository;

    public ProductPriceLookupService(PriceRuleRepository priceRuleRepository) {
        this.priceRuleRepository = priceRuleRepository;
    }

    @Cacheable(value = "productPrices", key = "#productId")
    @Transactional(readOnly = true)
    public long calculateBasePrice(UUID productId) {
        Instant now = Instant.now();
        PriceRule rule = priceRuleRepository.findActiveRuleByProductId(productId, now)
                .orElseThrow(() -> new PriceRuleNotFoundException(productId.toString()));
        return applyMultiplier(rule.getBasePriceCents(), rule.getMultiplier());
    }

    public static long applyMultiplier(long basePriceCents, BigDecimal multiplier) {
        return BigDecimal.valueOf(basePriceCents)
                .multiply(multiplier)
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
    }
}
