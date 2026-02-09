package com.instacommerce.audit.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class AuditEventBuilder {

    UUID id;
    String eventType;
    String sourceService;
    UUID actorId;
    String actorType;
    String resourceType;
    String resourceId;
    String action;
    Map<String, Object> details;
    String ipAddress;
    String userAgent;
    String correlationId;
    Instant createdAt;

    AuditEventBuilder() {
    }

    public AuditEventBuilder id(UUID id) {
        this.id = id;
        return this;
    }

    public AuditEventBuilder eventType(String eventType) {
        this.eventType = eventType;
        return this;
    }

    public AuditEventBuilder sourceService(String sourceService) {
        this.sourceService = sourceService;
        return this;
    }

    public AuditEventBuilder actorId(UUID actorId) {
        this.actorId = actorId;
        return this;
    }

    public AuditEventBuilder actorType(String actorType) {
        this.actorType = actorType;
        return this;
    }

    public AuditEventBuilder resourceType(String resourceType) {
        this.resourceType = resourceType;
        return this;
    }

    public AuditEventBuilder resourceId(String resourceId) {
        this.resourceId = resourceId;
        return this;
    }

    public AuditEventBuilder action(String action) {
        this.action = action;
        return this;
    }

    public AuditEventBuilder details(Map<String, Object> details) {
        this.details = details;
        return this;
    }

    public AuditEventBuilder ipAddress(String ipAddress) {
        this.ipAddress = ipAddress;
        return this;
    }

    public AuditEventBuilder userAgent(String userAgent) {
        this.userAgent = userAgent;
        return this;
    }

    public AuditEventBuilder correlationId(String correlationId) {
        this.correlationId = correlationId;
        return this;
    }

    public AuditEventBuilder createdAt(Instant createdAt) {
        this.createdAt = createdAt;
        return this;
    }

    public AuditEvent build() {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType is required");
        }
        if (sourceService == null || sourceService.isBlank()) {
            throw new IllegalArgumentException("sourceService is required");
        }
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action is required");
        }
        return new AuditEvent(this);
    }
}
