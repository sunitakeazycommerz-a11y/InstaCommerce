package com.instacommerce.fulfillment.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pick_items")
public class PickItem {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "pick_task_id", nullable = false)
    private PickTask pickTask;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "sku")
    private String sku;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "picked_qty", nullable = false)
    private int pickedQty;

    @Column(name = "unit_price_cents", nullable = false)
    private long unitPriceCents;

    @Column(name = "line_total_cents", nullable = false)
    private long lineTotalCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "pick_item_status")
    private PickItemStatus status = PickItemStatus.PENDING;

    @Column(name = "substitute_product_id")
    private UUID substituteProductId;

    private String note;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void prePersist() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public PickTask getPickTask() {
        return pickTask;
    }

    public void setPickTask(PickTask pickTask) {
        this.pickTask = pickTask;
    }

    public UUID getProductId() {
        return productId;
    }

    public void setProductId(UUID productId) {
        this.productId = productId;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public int getPickedQty() {
        return pickedQty;
    }

    public void setPickedQty(int pickedQty) {
        this.pickedQty = pickedQty;
    }

    public long getUnitPriceCents() {
        return unitPriceCents;
    }

    public void setUnitPriceCents(long unitPriceCents) {
        this.unitPriceCents = unitPriceCents;
    }

    public long getLineTotalCents() {
        return lineTotalCents;
    }

    public void setLineTotalCents(long lineTotalCents) {
        this.lineTotalCents = lineTotalCents;
    }

    public PickItemStatus getStatus() {
        return status;
    }

    public void setStatus(PickItemStatus status) {
        this.status = status;
    }

    public UUID getSubstituteProductId() {
        return substituteProductId;
    }

    public void setSubstituteProductId(UUID substituteProductId) {
        this.substituteProductId = substituteProductId;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
