package com.instacommerce.inventory.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "stock_items")
public class StockItem {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "store_id", nullable = false, columnDefinition = "uuid")
    private UUID storeId;

    @Column(name = "on_hand", nullable = false)
    private int onHand;

    @Column(name = "reserved", nullable = false)
    private int reserved;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    @PrePersist
    void prePersist() {
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getProductId() {
        return productId;
    }

    public void setProductId(UUID productId) {
        this.productId = productId;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public void setStoreId(UUID storeId) {
        this.storeId = storeId;
    }

    public int getOnHand() {
        return onHand;
    }

    public void setOnHand(int onHand) {
        this.onHand = onHand;
    }

    public int getReserved() {
        return reserved;
    }

    public void setReserved(int reserved) {
        this.reserved = reserved;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public void reserve(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Reservation quantity must be strictly positive: " + quantity);
        }
        int available = this.onHand - this.reserved;
        if (available < quantity) {
            throw new com.instacommerce.inventory.exception.InsufficientStockException(productId, available, quantity);
        }
        this.reserved += quantity;
    }

    public void confirm(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Confirmation quantity must be strictly positive: " + quantity);
        }
        if (this.reserved < quantity || this.onHand < quantity) {
            throw new com.instacommerce.inventory.exception.InsufficientStockException(
                productId, Math.min(this.reserved, this.onHand), quantity);
        }
        this.onHand -= quantity;
        this.reserved -= quantity;
    }

    public void releaseReserved(int quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException("Release quantity cannot be negative: " + quantity);
        }
        if (this.reserved < quantity) {
            throw new com.instacommerce.inventory.exception.InsufficientStockException(productId, this.reserved, quantity);
        }
        this.reserved -= quantity;
    }

    public void releaseConfirmed(int quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException("Release quantity cannot be negative: " + quantity);
        }
        this.onHand += quantity;
    }
}
