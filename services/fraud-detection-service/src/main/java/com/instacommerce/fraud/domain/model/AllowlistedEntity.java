package com.instacommerce.fraud.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "allowlisted_entities")
public class AllowlistedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "entity_type", nullable = false, length = 20)
    private String entityType;

    @Column(name = "entity_value", nullable = false)
    private String entityValue;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(name = "allowed_by")
    private String allowedBy;

    @Column(name = "allowed_at", nullable = false)
    private Instant allowedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean active = true;

    @PrePersist
    void prePersist() {
        if (allowedAt == null) {
            allowedAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getEntityValue() {
        return entityValue;
    }

    public void setEntityValue(String entityValue) {
        this.entityValue = entityValue;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getAllowedBy() {
        return allowedBy;
    }

    public void setAllowedBy(String allowedBy) {
        this.allowedBy = allowedBy;
    }

    public Instant getAllowedAt() {
        return allowedAt;
    }

    public void setAllowedAt(Instant allowedAt) {
        this.allowedAt = allowedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
