package com.instacommerce.inventory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.inventory.domain.model.OutboxEvent;
import com.instacommerce.inventory.repository.OutboxEventRepository;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.slf4j.MDC;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxService {
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String aggregateType, String aggregateId, String eventType,
                        Map<String, Object> payload) {
        publish(aggregateType, aggregateId, eventType, payload, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String aggregateType, String aggregateId, String eventType,
                        Map<String, Object> payload, String correlationId) {
        OutboxEvent event = new OutboxEvent();
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setEventType(eventType);
        event.setPayload(writePayload(payload));
        event.setCorrelationId(resolveCorrelationId(correlationId));
        outboxEventRepository.save(event);
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
