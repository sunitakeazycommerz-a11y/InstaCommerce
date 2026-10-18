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
@Table(name = "traffic_congestion_feeds")
public class TrafficCongestionFeed {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "zone_id", nullable = false, unique = true, length = 64)
    private String zoneId;

    @Column(name = "congestion_factor", nullable = false, precision = 4, scale = 2)
    private BigDecimal congestionFactor = new BigDecimal("1.00");

    @Column(name = "source", nullable = false, length = 64)
    private String source = "MUNICIPAL_FEED";

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

    public String getZoneId() {
        return zoneId;
    }

    public void setZoneId(String zoneId) {
        this.zoneId = zoneId;
    }

    public BigDecimal getCongestionFactor() {
        return congestionFactor;
    }

    public void setCongestionFactor(BigDecimal congestionFactor) {
        this.congestionFactor = congestionFactor;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
