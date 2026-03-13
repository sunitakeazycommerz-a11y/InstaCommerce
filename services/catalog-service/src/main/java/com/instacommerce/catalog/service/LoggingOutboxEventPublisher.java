package com.instacommerce.catalog.service;

import com.instacommerce.catalog.domain.model.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Fallback publisher that only logs outbox events without sending them.
 * Used when Kafka is not available (e.g. local development or tests).
 * In production, {@link KafkaOutboxEventPublisher} takes precedence as the {@code @Primary} bean.
 */
@Component
@ConditionalOnMissingBean(KafkaOutboxEventPublisher.class)
public class LoggingOutboxEventPublisher implements OutboxEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(LoggingOutboxEventPublisher.class);

    @Override
    public void publish(OutboxEvent event) {
        log.warn("STUB: Logging outbox event (Kafka publisher not available) id={} type={} aggregateType={} aggregateId={}",
            event.getId(),
            event.getEventType(),
            event.getAggregateType(),
            event.getAggregateId());
    }
}
