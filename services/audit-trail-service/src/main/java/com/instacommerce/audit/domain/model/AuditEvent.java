package com.instacommerce.audit.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false)
    private UUID id;

    @Column(name = "event_type", nullable = false, length = 100, updatable = false)
    private String eventType;

    @Column(name = "source_service", nullable = false, length = 50, updatable = false)
    private String sourceService;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "actor_type", length = 20, updatable = false)
    private String actorType;

    @Column(name = "resource_type", length = 100, updatable = false)
    private String resourceType;

    @Column(name = "resource_id", length = 255, updatable = false)
    private String resourceId;

    @Column(nullable = false, length = 100, updatable = false)
    private String action;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> details;

    @Column(name = "ip_address", length = 45, updatable = false)
    private String ipAddress;

    @Column(name = "user_agent", length = 512, updatable = false)
    private String userAgent;

    @Column(name = "correlation_id", length = 64, updatable = false)
    private String correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditEvent() {
    }

    AuditEvent(AuditEventBuilder builder) {
        this.id = builder.id;
        this.eventType = builder.eventType;
        this.sourceService = builder.sourceService;
        this.actorId = builder.actorId;
        this.actorType = builder.actorType;
        this.resourceType = builder.resourceType;
        this.resourceId = builder.resourceId;
        this.action = builder.action;
        this.details = builder.details;
        this.ipAddress = builder.ipAddress;
        this.userAgent = builder.userAgent;
        this.correlationId = builder.correlationId;
        this.createdAt = builder.createdAt != null ? builder.createdAt : Instant.now();
    }

    public static AuditEventBuilder builder() {
        return new AuditEventBuilder();
    }

    public UUID getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public String getSourceService() {
        return sourceService;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getActorType() {
        return actorType;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getAction() {
        return action;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
