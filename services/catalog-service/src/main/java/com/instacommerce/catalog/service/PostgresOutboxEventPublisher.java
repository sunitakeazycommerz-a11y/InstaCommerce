package com.instacommerce.catalog.service;

import com.instacommerce.catalog.domain.model.OutboxEvent;
import com.instacommerce.catalog.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional outbox event publisher that persists events into PostgreSQL
 * outbox_events table to be picked up by outbox-relay-service.
 * Takes primary precedence when Kafka direct publishing is not enabled.
 */
@Primary
@Component
@ConditionalOnMissingBean(KafkaOutboxEventPublisher.class)
public class PostgresOutboxEventPublisher implements OutboxEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(PostgresOutboxEventPublisher.class);

    private final OutboxEventRepository outboxEventRepository;

    public PostgresOutboxEventPublisher(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(OutboxEvent event) {
        if (event == null) {
            return;
        }
        log.debug("Persisting outbox event to Postgres outbox_events table: id={} eventType={}",
            event.getId(), event.getEventType());
        outboxEventRepository.save(event);
    }
}
