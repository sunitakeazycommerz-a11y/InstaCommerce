package com.instacommerce.identity.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "notification_preferences")
public class NotificationPreference {
    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "email_opt_out", nullable = false)
    private boolean emailOptOut;

    @Column(name = "sms_opt_out", nullable = false)
    private boolean smsOptOut;

    @Column(name = "push_opt_out", nullable = false)
    private boolean pushOptOut;

    @Column(name = "marketing_opt_out", nullable = false)
    private boolean marketingOptOut;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public boolean isEmailOptOut() {
        return emailOptOut;
    }

    public void setEmailOptOut(boolean emailOptOut) {
        this.emailOptOut = emailOptOut;
    }

    public boolean isSmsOptOut() {
        return smsOptOut;
    }

    public void setSmsOptOut(boolean smsOptOut) {
        this.smsOptOut = smsOptOut;
    }

    public boolean isPushOptOut() {
        return pushOptOut;
    }

    public void setPushOptOut(boolean pushOptOut) {
        this.pushOptOut = pushOptOut;
    }

    public boolean isMarketingOptOut() {
        return marketingOptOut;
    }

    public void setMarketingOptOut(boolean marketingOptOut) {
        this.marketingOptOut = marketingOptOut;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
