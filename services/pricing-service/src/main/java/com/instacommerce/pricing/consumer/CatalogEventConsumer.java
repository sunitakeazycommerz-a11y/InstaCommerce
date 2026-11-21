package com.instacommerce.pricing.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.contracts.events.EventEnvelope;
import com.instacommerce.contracts.topics.TopicNames;
import com.instacommerce.pricing.domain.PriceRule;
import com.instacommerce.pricing.repository.PriceRuleRepository;
import java.math.BigDecimal;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CatalogEventConsumer {
    private static final Logger logger = LoggerFactory.getLogger(CatalogEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final PriceRuleRepository priceRuleRepository;
    private final CacheManager cacheManager;

    @Autowired
    public CatalogEventConsumer(ObjectMapper objectMapper,
                                PriceRuleRepository priceRuleRepository,
                                CacheManager cacheManager) {
        this.objectMapper = objectMapper;
        this.priceRuleRepository = priceRuleRepository;
        this.cacheManager = cacheManager;
    }

    @KafkaListener(topics = TopicNames.CATALOG_EVENTS, groupId = "pricing-service-catalog")
    @Transactional
    public void onCatalogEvent(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = parseEnvelope(record);
        if (!"ProductCreated".equals(envelope.eventType()) && !"ProductUpdated".equals(envelope.eventType())) {
            return;
        }
        CatalogProductEvent event = parseProductEvent(envelope, record);
        if (event.id() == null) {
            logger.warn("Catalog event missing product id");
            return;
        }

        if (cacheManager != null) {
            var pricesCache = cacheManager.getCache("productPrices");
            if (pricesCache != null) {
                pricesCache.evict(event.id());
            }
            var rulesCache = cacheManager.getCache("priceRules");
            if (rulesCache != null) {
                rulesCache.evict(event.id());
            }
            logger.debug("Evicted price caches for productId={}", event.id());
        }

        Instant now = Instant.now();
        if (!event.active()) {
            for (PriceRule existing : priceRuleRepository.findByProductIdAndActiveTrue(event.id())) {
                existing.setActive(false);
                if (existing.getEffectiveTo() == null || existing.getEffectiveTo().isAfter(now)) {
                    existing.setEffectiveTo(now);
                }
                priceRuleRepository.save(existing);
            }
            logger.info("Deactivated price rules for inactive product id={}", event.id());
            return;
        }

        Long incomingPriceCents = event.basePriceCents();
        var existingOpt = priceRuleRepository.findActiveRuleByProductId(event.id(), now);
        if (existingOpt.isEmpty()) {
            long resolvedPrice = incomingPriceCents != null && incomingPriceCents >= 0 ? incomingPriceCents : 0L;
            PriceRule rule = new PriceRule();
            rule.setProductId(event.id());
            rule.setBasePriceCents(resolvedPrice);
            rule.setEffectiveFrom(now);
            rule.setRuleType("STANDARD");
            rule.setMultiplier(BigDecimal.ONE);
            rule.setActive(true);
            priceRuleRepository.save(rule);
            logger.info("Created base price rule for product id={} basePriceCents={}", event.id(), resolvedPrice);
        } else if (incomingPriceCents != null && incomingPriceCents >= 0) {
            PriceRule currentRule = existingOpt.get();
            if ("STANDARD".equalsIgnoreCase(currentRule.getRuleType())
                    && currentRule.getBasePriceCents() != incomingPriceCents) {
                currentRule.setBasePriceCents(incomingPriceCents);
                priceRuleRepository.save(currentRule);
                logger.info("Updated standard base price rule for product id={} basePriceCents={}",
                        event.id(), incomingPriceCents);
            }
        }
    }

    private EventEnvelope parseEnvelope(ConsumerRecord<String, String> record) {
        try {
            return objectMapper.readValue(record.value(), EventEnvelope.class);
        } catch (JsonProcessingException ex) {
            logger.error("Failed to deserialize catalog event envelope at offset={}, partition={}",
                    record.offset(), record.partition(), ex);
            throw new IllegalStateException("Failed to deserialize catalog event envelope", ex);
        }
    }

    private CatalogProductEvent parseProductEvent(EventEnvelope envelope, ConsumerRecord<String, String> record) {
        try {
            return objectMapper.treeToValue(envelope.payload(), CatalogProductEvent.class);
        } catch (JsonProcessingException ex) {
            logger.error("Failed to parse catalog product payload at offset={}, partition={}",
                    record.offset(), record.partition(), ex);
            throw new IllegalStateException("Failed to parse catalog product payload", ex);
        }
    }
}
