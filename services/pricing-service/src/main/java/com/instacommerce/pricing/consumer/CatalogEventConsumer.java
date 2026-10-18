package com.instacommerce.pricing.consumer;

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
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CatalogEventConsumer {
    private static final Logger logger = LoggerFactory.getLogger(CatalogEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final PriceRuleRepository priceRuleRepository;
    private final org.springframework.cache.CacheManager cacheManager;

    @org.springframework.beans.factory.annotation.Autowired
    public CatalogEventConsumer(ObjectMapper objectMapper,
                                PriceRuleRepository priceRuleRepository,
                                org.springframework.cache.CacheManager cacheManager) {
        this.objectMapper = objectMapper;
        this.priceRuleRepository = priceRuleRepository;
        this.cacheManager = cacheManager;
    }

    public CatalogEventConsumer(ObjectMapper objectMapper, PriceRuleRepository priceRuleRepository) {
        this(objectMapper, priceRuleRepository, null);
    }

    @KafkaListener(topics = TopicNames.CATALOG_EVENTS, groupId = "pricing-service-catalog")
    @Transactional
    public void onCatalogEvent(ConsumerRecord<String, String> record) {
        try {
            EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
            if (!"ProductCreated".equals(envelope.eventType()) && !"ProductUpdated".equals(envelope.eventType())) {
                return;
            }
            CatalogProductEvent event = objectMapper.treeToValue(envelope.payload(), CatalogProductEvent.class);
            if (event.id() == null) {
                logger.warn("Catalog event missing product id");
                return;
            }

            // Evict product prices and rules cache
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
            // Only create a base price rule if none exists
            if (priceRuleRepository.findActiveRuleByProductId(event.id(), now).isEmpty()) {
                PriceRule rule = new PriceRule();
                rule.setProductId(event.id());
                rule.setBasePriceCents(0);
                rule.setEffectiveFrom(now);
                rule.setRuleType("STANDARD");
                rule.setMultiplier(BigDecimal.ONE);
                rule.setActive(true);
                priceRuleRepository.save(rule);
                logger.info("Created base price rule for new product id={}", event.id());
            }
        } catch (Exception ex) {
            logger.error("Failed to process catalog event: skipping record at offset={}, partition={}",
                record.offset(), record.partition(), ex);
        }
    }
}
