package com.instacommerce.catalog.service;

import com.instacommerce.catalog.domain.model.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LoggingOutboxEventPublisher implements OutboxEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(LoggingOutboxEventPublisher.class);

    @Override
    public void publish(OutboxEvent event) {
        log.info("Publishing outbox event id={} type={} aggregateType={} aggregateId={}",
            event.getId(),
            event.getEventType(),
            event.getAggregateType(),
            event.getAggregateId());
    }
}
