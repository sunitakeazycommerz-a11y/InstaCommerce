package com.instacommerce.routing.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.routing.domain.model.OutboxEvent;
import com.instacommerce.routing.repository.OutboxEventRepository;
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

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String aggregateType, String aggregateId, String eventType, Object payload) {
        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setAggregateType(aggregateType);
        outboxEvent.setAggregateId(aggregateId);
        outboxEvent.setEventType(eventType);
        outboxEvent.setPayload(writePayload(payload));
        outboxEventRepository.save(outboxEvent);
    }

    private String writePayload(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize outbox event", ex);
        }
    }
}
