package com.instacommerce.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.payment.domain.model.OutboxEvent;
import com.instacommerce.payment.repository.OutboxEventRepository;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxService {
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Backward-compatible overload — delegates to the envelope-aware method
     * with correlationId resolved from MDC.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String aggregateType, String aggregateId, String eventType, Object payload) {
        publish(aggregateType, aggregateId, eventType, payload, null);
    }

    /**
     * Publishes an outbox event with full envelope fields per contracts/README.md.
     *
     * @param correlationId explicit correlation ID; falls back to MDC if null
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String aggregateType, String aggregateId, String eventType,
                        Object payload, String correlationId) {
        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setAggregateType(aggregateType);
        outboxEvent.setAggregateId(aggregateId);
        outboxEvent.setEventType(eventType);
        outboxEvent.setPayload(writePayload(payload));
        outboxEvent.setCorrelationId(resolveCorrelationId(correlationId));
        // eventId, schemaVersion, sourceService, eventTimestamp are set by @PrePersist
        outboxEventRepository.save(outboxEvent);
    }

    private String resolveCorrelationId(String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        String fromMdc = MDC.get("correlationId");
        if (fromMdc != null && !fromMdc.isBlank()) {
            return fromMdc;
        }
        return MDC.get("X-Correlation-ID");
    }

    private String writePayload(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize outbox event", ex);
        }
    }
}
