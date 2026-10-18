package com.instacommerce.routing.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "weather_conditions")
public class WeatherCondition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "store_id", nullable = false, unique = true)
    private UUID storeId;

    @Column(name = "condition_type", nullable = false, length = 64)
    private String conditionType = "CLEAR";

    @Column(name = "speed_penalty_multiplier", nullable = false, precision = 4, scale = 2)
    private BigDecimal speedPenaltyMultiplier = new BigDecimal("1.00");

    @Column(name = "monsoon_delay_banner", nullable = false)
    private boolean monsoonDelayBanner = false;

    @Column(name = "notes")
    private String notes;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public void setStoreId(UUID storeId) {
        this.storeId = storeId;
    }

    public String getConditionType() {
        return conditionType;
    }

    public void setConditionType(String conditionType) {
        this.conditionType = conditionType;
    }

    public BigDecimal getSpeedPenaltyMultiplier() {
        return speedPenaltyMultiplier;
    }

    public void setSpeedPenaltyMultiplier(BigDecimal speedPenaltyMultiplier) {
        this.speedPenaltyMultiplier = speedPenaltyMultiplier;
    }

    public boolean isMonsoonDelayBanner() {
        return monsoonDelayBanner;
    }

    public void setMonsoonDelayBanner(boolean monsoonDelayBanner) {
        this.monsoonDelayBanner = monsoonDelayBanner;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
