package com.instacommerce.riderfleet.domain.model;

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
@Table(name = "rider_availability")
public class RiderAvailability {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "rider_id", nullable = false, unique = true)
    private UUID riderId;

    @Column(name = "is_available", nullable = false)
    private boolean available;

    @Column(name = "current_lat", precision = 10, scale = 8)
    private BigDecimal currentLat;

    @Column(name = "current_lng", precision = 11, scale = 8)
    private BigDecimal currentLng;

    @Column(name = "store_id")
    private UUID storeId;

    @Column(name = "last_updated")
    private Instant lastUpdated;

    @PrePersist
    void prePersist() {
        if (lastUpdated == null) {
            lastUpdated = Instant.now();
        }
    }

    @PreUpdate
    void preUpdate() {
        lastUpdated = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getRiderId() {
        return riderId;
    }

    public void setRiderId(UUID riderId) {
        this.riderId = riderId;
    }

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public BigDecimal getCurrentLat() {
        return currentLat;
    }

    public void setCurrentLat(BigDecimal currentLat) {
        this.currentLat = currentLat;
    }

    public BigDecimal getCurrentLng() {
        return currentLng;
    }

    public void setCurrentLng(BigDecimal currentLng) {
        this.currentLng = currentLng;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public void setStoreId(UUID storeId) {
        this.storeId = storeId;
    }

    public Instant getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(Instant lastUpdated) {
        this.lastUpdated = lastUpdated;
    }
}
