package com.instacommerce.warehouse.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "warehouse_bins",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uq_warehouse_bins_location",
            columnNames = {"store_id", "aisle", "rack", "shelf", "bin_number"}
        )
    }
)
public class WarehouseBin {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(nullable = false, length = 20)
    private String aisle;

    @Column(nullable = false, length = 20)
    private String rack;

    @Column(nullable = false, length = 20)
    private String shelf;

    @Column(name = "bin_number", nullable = false, length = 20)
    private String binNumber;

    @Column(length = 100)
    private String sku;

    @Column(name = "max_volume_cm3", nullable = false)
    private int maxVolumeCm3 = 50000;

    @Column(name = "current_volume_cm3", nullable = false)
    private int currentVolumeCm3 = 0;

    @Column(name = "max_weight_grams", nullable = false)
    private int maxWeightGrams = 25000;

    @Column(name = "current_weight_grams", nullable = false)
    private int currentWeightGrams = 0;

    @Column(nullable = false)
    private boolean occupied = false;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Store getStore() { return store; }
    public void setStore(Store store) { this.store = store; }

    public String getAisle() { return aisle; }
    public void setAisle(String aisle) { this.aisle = aisle; }

    public String getRack() { return rack; }
    public void setRack(String rack) { this.rack = rack; }

    public String getShelf() { return shelf; }
    public void setShelf(String shelf) { this.shelf = shelf; }

    public String getBinNumber() { return binNumber; }
    public void setBinNumber(String binNumber) { this.binNumber = binNumber; }

    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }

    public int getMaxVolumeCm3() { return maxVolumeCm3; }
    public void setMaxVolumeCm3(int maxVolumeCm3) { this.maxVolumeCm3 = maxVolumeCm3; }

    public int getCurrentVolumeCm3() { return currentVolumeCm3; }
    public void setCurrentVolumeCm3(int currentVolumeCm3) { this.currentVolumeCm3 = currentVolumeCm3; }

    public int getMaxWeightGrams() { return maxWeightGrams; }
    public void setMaxWeightGrams(int maxWeightGrams) { this.maxWeightGrams = maxWeightGrams; }

    public int getCurrentWeightGrams() { return currentWeightGrams; }
    public void setCurrentWeightGrams(int currentWeightGrams) { this.currentWeightGrams = currentWeightGrams; }

    public boolean isOccupied() { return occupied; }
    public void setOccupied(boolean occupied) { this.occupied = occupied; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
