package com.instacommerce.inventory.domain.model;

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
@Table(name = "sku_safety_stock_buffers")
public class SkuSafetyStockBuffer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "z_score", nullable = false, precision = 4, scale = 2)
    private BigDecimal zScore;

    @Column(name = "std_dev_demand", nullable = false, precision = 10, scale = 2)
    private BigDecimal stdDevDemand;

    @Column(name = "lead_time_days", nullable = false)
    private int leadTimeDays;

    @Column(name = "calculated_safety_stock", nullable = false)
    private int calculatedSafetyStock;

    @Column(name = "reorder_threshold", nullable = false)
    private int reorderThreshold;

    @Column(name = "last_reorder_event_at")
    private Instant lastReorderEventAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
        recalculate();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
        recalculate();
    }

    public void recalculate() {
        if (zScore != null && stdDevDemand != null && leadTimeDays > 0) {
            double z = zScore.doubleValue();
            double sigma = stdDevDemand.doubleValue();
            double leadTimeSqrt = Math.sqrt(leadTimeDays);
            // Formula from Issue #579: safety_stock = z_score * std_dev_demand * sqrt(lead_time_days)
            this.calculatedSafetyStock = Math.max(1, (int) Math.ceil(z * sigma * leadTimeSqrt));
            // Reorder threshold includes safety buffer + baseline expected lead-time consumption
            int leadTimeBuffer = (int) Math.ceil(sigma * leadTimeDays);
            this.reorderThreshold = Math.max(this.calculatedSafetyStock, this.calculatedSafetyStock + leadTimeBuffer);
        } else if (calculatedSafetyStock <= 0) {
            this.calculatedSafetyStock = 2; // Baseline fallback
            this.reorderThreshold = 5;
        }
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

    public UUID getProductId() {
        return productId;
    }

    public void setProductId(UUID productId) {
        this.productId = productId;
    }

    public BigDecimal getZScore() {
        return zScore;
    }

    public void setZScore(BigDecimal zScore) {
        this.zScore = zScore;
    }

    public BigDecimal getStdDevDemand() {
        return stdDevDemand;
    }

    public void setStdDevDemand(BigDecimal stdDevDemand) {
        this.stdDevDemand = stdDevDemand;
    }

    public int getLeadTimeDays() {
        return leadTimeDays;
    }

    public void setLeadTimeDays(int leadTimeDays) {
        this.leadTimeDays = leadTimeDays;
    }

    public int getCalculatedSafetyStock() {
        return calculatedSafetyStock;
    }

    public void setCalculatedSafetyStock(int calculatedSafetyStock) {
        this.calculatedSafetyStock = calculatedSafetyStock;
    }

    public int getReorderThreshold() {
        return reorderThreshold;
    }

    public void setReorderThreshold(int reorderThreshold) {
        this.reorderThreshold = reorderThreshold;
    }

    public Instant getLastReorderEventAt() {
        return lastReorderEventAt;
    }

    public void setLastReorderEventAt(Instant lastReorderEventAt) {
        this.lastReorderEventAt = lastReorderEventAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
