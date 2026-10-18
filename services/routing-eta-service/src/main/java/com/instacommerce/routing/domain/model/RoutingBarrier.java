package com.instacommerce.routing.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "routing_barriers")
public class RoutingBarrier {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "reason")
    private String reason;

    @Column(name = "min_lat", nullable = false, precision = 10, scale = 8)
    private BigDecimal minLat;

    @Column(name = "min_lng", nullable = false, precision = 11, scale = 8)
    private BigDecimal minLng;

    @Column(name = "max_lat", nullable = false, precision = 10, scale = 8)
    private BigDecimal maxLat;

    @Column(name = "max_lng", nullable = false, precision = 11, scale = 8)
    private BigDecimal maxLng;

    @Column(name = "detour_penalty_km", nullable = false, precision = 6, scale = 2)
    private BigDecimal detourPenaltyKm = new BigDecimal("3.50");

    @Column(name = "detour_penalty_minutes", nullable = false)
    private int detourPenaltyMinutes = 12;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /**
     * Checks if a line segment between (lat1, lng1) and (lat2, lng2) intersects or enters the barrier bounding box.
     */
    public boolean intersects(double lat1, double lng1, double lat2, double lng2) {
        if (!isActive) {
            return false;
        }
        if (expiresAt != null && expiresAt.isBefore(Instant.now())) {
            return false;
        }

        double bMinLat = minLat.doubleValue();
        double bMaxLat = maxLat.doubleValue();
        double bMinLng = minLng.doubleValue();
        double bMaxLng = maxLng.doubleValue();

        // Check if either endpoint is inside barrier box
        if ((lat1 >= bMinLat && lat1 <= bMaxLat && lng1 >= bMinLng && lng1 <= bMaxLng) ||
            (lat2 >= bMinLat && lat2 <= bMaxLat && lng2 >= bMinLng && lng2 <= bMaxLng)) {
            return true;
        }

        // Bounding box overlap test
        double segMinLat = Math.min(lat1, lat2);
        double segMaxLat = Math.max(lat1, lat2);
        double segMinLng = Math.min(lng1, lng2);
        double segMaxLng = Math.max(lng1, lng2);

        return !(segMaxLat < bMinLat || segMinLat > bMaxLat || segMaxLng < bMinLng || segMinLng > bMaxLng);
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public BigDecimal getMinLat() {
        return minLat;
    }

    public void setMinLat(BigDecimal minLat) {
        this.minLat = minLat;
    }

    public BigDecimal getMinLng() {
        return minLng;
    }

    public void setMinLng(BigDecimal minLng) {
        this.minLng = minLng;
    }

    public BigDecimal getMaxLat() {
        return maxLat;
    }

    public void setMaxLat(BigDecimal maxLat) {
        this.maxLat = maxLat;
    }

    public BigDecimal getMaxLng() {
        return maxLng;
    }

    public void setMaxLng(BigDecimal maxLng) {
        this.maxLng = maxLng;
    }

    public BigDecimal getDetourPenaltyKm() {
        return detourPenaltyKm;
    }

    public void setDetourPenaltyKm(BigDecimal detourPenaltyKm) {
        this.detourPenaltyKm = detourPenaltyKm;
    }

    public int getDetourPenaltyMinutes() {
        return detourPenaltyMinutes;
    }

    public void setDetourPenaltyMinutes(int detourPenaltyMinutes) {
        this.detourPenaltyMinutes = detourPenaltyMinutes;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        isActive = active;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
